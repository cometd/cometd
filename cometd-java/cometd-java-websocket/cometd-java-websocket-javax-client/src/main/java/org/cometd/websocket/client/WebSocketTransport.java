/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.websocket.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.websocket.client.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class WebSocketTransport extends AbstractWebSocketTransport {
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

        // JSR 356 does not expose a way to set the connect timeout - ignored
        _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketContainer.getDefaultMaxTextMessageBufferSize());
        _webSocketContainer.setDefaultMaxTextMessageBufferSize(maxMessageSize);

        _webSocketSupported = true;
        _webSocketConnected = false;
    }

    @Override
    protected Delegate connect(String uri, TransportListener listener, List<Mutable> messages) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Opening websocket session to {}", uri);
            }
            _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
            ClientEndpointConfig.Configurator configurator = new Configurator();
            String protocol = getProtocol();
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .preferredSubprotocols(protocol == null ? null : Collections.singletonList(protocol))
                    .configurator(configurator).build();
            Delegate delegate = connect(_webSocketContainer, config, uri);
            _webSocketConnected = true;
            return delegate;
        } catch (ConnectException | SocketTimeoutException | UnresolvedAddressException x) {
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

    protected class WebSocketDelegate extends Delegate implements MessageHandler.Whole<String> {
        private final Endpoint _endpoint = new WebSocketEndpoint();
        private Session _session;

        private void onOpen(Session session) {
            synchronized (this) {
                _session = session;
            }
            session.addMessageHandler(this);
            if (logger.isDebugEnabled()) {
                logger.debug("Opened websocket session {}", session);
            }
        }

        @Override
        public void onMessage(String data) {
            onData(data);
        }

        @Override
        public void send(String content) {
            Session session;
            synchronized (this) {
                session = _session;
            }
            try {
                if (session == null) {
                    throw new IOException("Unconnected");
                }

                // Blocking sends for the client, using
                // AsyncRemote to allow concurrent sends.
                session.getAsyncRemote().sendText(content).get();
            } catch (Throwable x) {
                fail(x, "Exception");
            }
        }

        @Override
        protected void shutdown(String reason) {
            Session session;
            synchronized (this) {
                session = _session;
                close();
            }
            if (session != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Closing ({}) websocket session {}", reason, session);
                }
                try {
                    // Limits of the WebSocket APIs, otherwise an exception is thrown.
                    reason = reason.substring(0, Math.min(reason.length(), 30));
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
                } catch (Throwable x) {
                    logger.trace("Could not close websocket session " + session, x);
                }
            }
        }

        @Override
        protected boolean isOpen() {
            synchronized (this) {
                return _session != null;
            }
        }

        @Override
        protected void close() {
            synchronized (this) {
                _session = null;
            }
        }

        private class WebSocketEndpoint extends Endpoint {
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
            CookieStore cookieStore = getCookieStore();
            List<HttpCookie> cookies = cookieStore.get(URI.create(getURL()));
            if (!cookies.isEmpty()) {
                List<String> cookieHeader = headers.get("Cookie");
                if (cookieHeader == null) {
                    cookieHeader = headers.get("cookie");
                }
                if (cookieHeader == null) {
                    headers.put("Cookie", cookieHeader = new ArrayList<>());
                }
                for (HttpCookie cookie : cookies) {
                    cookieHeader.add(cookie.getName() + "=" + cookie.getValue());
                }
            }
        }

        @Override
        public void afterResponse(HandshakeResponse hr) {
            Map<String, List<String>> headers = hr.getHeaders();

            storeCookies(headers);

            _webSocketSupported = false;
            // Must do case-insensitive search.
            for (String name : headers.keySet()) {
                if (HandshakeResponse.SEC_WEBSOCKET_ACCEPT.equalsIgnoreCase(name)) {
                    _webSocketSupported = true;
                    break;
                }
            }
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new WebSocketTransport(url, options, scheduler, container);
        }
    }
}
