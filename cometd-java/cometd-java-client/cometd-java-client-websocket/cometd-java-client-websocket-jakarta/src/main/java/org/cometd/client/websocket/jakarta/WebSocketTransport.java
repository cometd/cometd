/*
 * Copyright (c) 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.client.websocket.jakarta;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketTransport extends AbstractWebSocketTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketTransport.class);

    private final WebSocketContainer _webSocketContainer;
    private boolean _webSocketSupported;
    private boolean _webSocketConnected;

    public WebSocketTransport(Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketContainer webSocketContainer) {
        this(null, options, scheduler, webSocketContainer);
    }

    public WebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketContainer webSocketContainer) {
        super(url, options, scheduler);
        _webSocketContainer = webSocketContainer;
        _webSocketSupported = true;
    }

    @Override
    public boolean accept(String version) {
        return _webSocketSupported;
    }

    @Override
    public void init() {
        super.init();

        // Jakarta APIs do not expose a way to set the connect timeout - ignored
        _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketContainer.getDefaultMaxTextMessageBufferSize());
        _webSocketContainer.setDefaultMaxTextMessageBufferSize(maxMessageSize);

        _webSocketSupported = true;
        _webSocketConnected = false;
    }

    @Override
    protected Delegate connect(String uri, TransportListener listener, List<Mutable> messages) {
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Opening websocket session to {}", uri);
            }
            _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
            ClientEndpointConfig.Builder config = ClientEndpointConfig.Builder.create();
            String protocol = getProtocol();
            if (protocol != null) {
                config = config.preferredSubprotocols(List.of(protocol));
            }
            ClientEndpointConfig.Configurator configurator = new Configurator();
            config = config.configurator(configurator);
            Delegate delegate = connect(_webSocketContainer, config.build(), uri);
            _webSocketConnected = true;
            return delegate;
        } catch (ConnectException | SocketTimeoutException | UnresolvedAddressException | UnknownHostException x) {
            // Cannot connect, assume the server supports WebSocket until proved otherwise
            listener.onFailure(x, messages);
        } catch (Throwable x) {
            _webSocketSupported = isStickyReconnect() && _webSocketConnected;
            listener.onFailure(x, messages);
        }
        return null;
    }

    protected Delegate connect(WebSocketContainer container, ClientEndpointConfig configuration, String uri) throws IOException {
        try {
            WebSocketDelegate delegate = newDelegate();
            container.connectToServer(delegate._endpoint, configuration, new URI(uri));
            return delegate;
        } catch (DeploymentException | URISyntaxException x) {
            throw new IOException(x);
        }
    }

    protected WebSocketDelegate newDelegate() {
        return new WebSocketDelegate();
    }

    protected void onHandshakeRequest(Map<String, List<String>> headers) {
        if (isPerMessageDeflateEnabled()) {
            ArrayList<String> extensions = new ArrayList<>();
            headers.compute("Sec-WebSocket-Extensions", (k, v) -> {
                if (v != null) {
                    extensions.addAll(v);
                }
                extensions.add("permessage-deflate");
                return extensions;
            });
        }
        List<HttpCookie> cookies = getCookies(URI.create(getURL()));
        if (!cookies.isEmpty()) {
            List<String> cookieHeader = headers.get(COOKIE_HEADER);
            if (cookieHeader == null) {
                cookieHeader = headers.get(COOKIE_HEADER.toLowerCase(Locale.ENGLISH));
            }
            if (cookieHeader == null) {
                headers.put(COOKIE_HEADER, cookieHeader = new ArrayList<>());
            }
            for (HttpCookie cookie : cookies) {
                cookieHeader.add(cookie.getName() + "=" + cookie.getValue());
            }
        }
    }

    protected void onHandshakeResponse(HandshakeResponse response) {
        Map<String, List<String>> headers = response.getHeaders();
        storeCookies(URI.create(getURL()), headers);
        _webSocketSupported = false;
        // Must do case-insensitive search.
        for (String name : headers.keySet()) {
            if (HandshakeResponse.SEC_WEBSOCKET_ACCEPT.equalsIgnoreCase(name)) {
                _webSocketSupported = true;
                break;
            }
        }
    }

    public class WebSocketDelegate extends Delegate implements MessageHandler.Whole<String> {
        private final Endpoint _endpoint = new WebSocketEndpoint();
        private Session _session;

        private void onOpen(Session session) {
            locked(() -> _session = session);
            session.addMessageHandler(this);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Opened websocket session {}", session);
            }
        }

        @Override
        public void onMessage(String data) {
            onData(data);
        }

        @Override
        public void send(String content) {
            Session session = locked(() -> _session);
            try {
                if (session == null) {
                    throw new IOException("Unconnected");
                }

                // Blocking sends for the client, using
                // AsyncRemote to allow concurrent sends.
                // The send() should be failed by the implementation, but
                // will use Future.get(timeout) to avoid implementation bugs.
                long timeout = getIdleTimeout() + 1000;
                session.getAsyncRemote().sendText(content).get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException x) {
                fail(x, "Timeout");
            } catch (ExecutionException x) {
                fail(x.getCause(), "Exception");
            } catch (Throwable x) {
                fail(x, "Failure");
            }
        }

        @Override
        protected void shutdown(String reason) {
            Session session = locked(() -> {
                Session result = _session;
                close();
                return result;
            });
            if (session != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Closing ({}) websocket session {}", reason, session);
                }
                try {
                    // Limits of the WebSocket APIs, otherwise an exception is thrown.
                    reason = trimCloseReason(reason);
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
                } catch (Throwable x) {
                    LOGGER.trace("Could not close websocket session " + session, x);
                }
            }
        }

        @Override
        protected boolean isOpen() {
            return locked(() -> super.isOpen() && _session != null);
        }

        @Override
        protected void close() {
            locked(() -> _session = null);
        }

        public class WebSocketEndpoint extends Endpoint {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                WebSocketDelegate.this.onOpen(session);
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                WebSocketDelegate.this.onClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
            }

            @Override
            public void onError(Session session, Throwable failure) {
                failMessages(failure);
            }
        }
    }

    private class Configurator extends ClientEndpointConfig.Configurator {
        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            onHandshakeRequest(headers);
        }

        @Override
        public void afterResponse(HandshakeResponse hr) {
            onHandshakeResponse(hr);
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        public Factory() {
            // The WebSocketContainer comes from the Servlet Container,
            // so its lifecycle cannot be managed by this class due to
            // classloader differences; we just add it for dump() purposes.
            addBean(container, false);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new WebSocketTransport(url, options, scheduler, container);
        }
    }
}
