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
package org.cometd.client.websocket.jetty;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.common.AbstractWebSocketTransport;
import org.cometd.common.TransportException;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketTransport extends AbstractWebSocketTransport implements UpgradeListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(JettyWebSocketTransport.class);

    private final WebSocketClient _webSocketClient;
    private boolean _webSocketSupported;
    private boolean _webSocketConnected;

    public JettyWebSocketTransport(Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketClient webSocketClient) {
        this(null, options, scheduler, webSocketClient);
    }

    public JettyWebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketClient webSocketClient) {
        super(url, options, scheduler);
        _webSocketClient = webSocketClient;
        _webSocketSupported = true;
    }

    @Override
    public boolean accept(String version) {
        return _webSocketSupported;
    }

    @Override
    public void init() {
        super.init();

        // Keep these 2 deprecated methods as is, because calling
        // _webSocketClient.getHttpClient() throws a LinkageError.
        _webSocketClient.setConnectTimeout(getConnectTimeout());
        _webSocketClient.setCookieStore(getCookieStore());

        _webSocketClient.getPolicy().setIdleTimeout(getIdleTimeout());
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClient.getPolicy().getMaxTextMessageSize());
        _webSocketClient.getPolicy().setMaxTextMessageSize(maxMessageSize);

        _webSocketSupported = true;
        _webSocketConnected = false;
    }

    @Override
    protected Delegate connect(String uri, TransportListener listener, List<Mutable> messages) {
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Opening websocket session to {}", uri);
            }
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            String protocol = getProtocol();
            if (protocol != null) {
                request.setSubProtocols(protocol);
            }
            if (isPerMessageDeflateEnabled()) {
                request.addExtensions("permessage-deflate");
            }
            Delegate delegate = connect(_webSocketClient, request, uri);
            _webSocketConnected = true;
            return delegate;
        } catch (ConnectException | SocketTimeoutException | UnresolvedAddressException | UnknownHostException x) {
            // Cannot connect, assume the server supports WebSocket until proved otherwise
            listener.onFailure(x, messages);
        } catch (UpgradeException x) {
            _webSocketSupported = false;
            Map<String, Object> failure = new HashMap<>(2);
            failure.put("websocketCode", 1002);
            failure.put("httpCode", x.getResponseStatusCode());
            listener.onFailure(new TransportException(x, failure), messages);
        } catch (Throwable x) {
            _webSocketSupported = isStickyReconnect() && _webSocketConnected;
            listener.onFailure(x, messages);
        }
        return null;
    }

    protected Delegate connect(WebSocketClient client, ClientUpgradeRequest request, String uri) throws IOException, InterruptedException {
        try {
            Delegate delegate = newDelegate();
            // The connect() should be failed by the WebSocket implementation,
            // but will use Future.get(timeout) to avoid implementation bugs.
            long timeout = getConnectTimeout() + 1000;
            client.connect(delegate, new URI(uri), request, this).get(timeout, TimeUnit.MILLISECONDS);
            return delegate;
        } catch (TimeoutException e) {
            throw new ConnectException("Connect timeout");
        } catch (ExecutionException x) {
            Throwable cause = x.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException)cause;
            }
            if (cause instanceof IOException) {
                throw (IOException)cause;
            }
            throw new IOException(cause);
        } catch (URISyntaxException x) {
            throw new IOException(x);
        }
    }

    protected Delegate newDelegate() {
        return new JettyWebSocketDelegate();
    }

    @Override
    public void onHandshakeRequest(UpgradeRequest request) {
    }

    @Override
    public void onHandshakeResponse(UpgradeResponse response) {
        storeCookies(URI.create(getURL()), response.getHeaders());
    }

    protected class JettyWebSocketDelegate extends Delegate implements WebSocketListener {
        private Session _session;

        @Override
        public void onWebSocketConnect(Session session) {
            locked(() -> _session = session);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Opened websocket session {}", session);
            }
        }

        @Override
        public void onWebSocketClose(int closeCode, String reason) {
            onClose(closeCode, reason);
        }

        @Override
        public void onWebSocketText(String data) {
            onData(data);
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
        }

        @Override
        public void onWebSocketError(Throwable failure) {
            failMessages(failure);
        }

        @Override
        public void send(String content) {
            Session session = locked(() -> _session);
            try {
                if (session == null) {
                    throw new IOException("Unconnected");
                }

                // Blocking async sends for the client to allow concurrent sends.
                // The send() should be failed by the implementation, but
                // will use Future.get(timeout) to avoid implementation bugs.
                long timeout = getIdleTimeout() + 1000;
                session.getRemote().sendStringByFuture(content).get(timeout, TimeUnit.MILLISECONDS);
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
                    LOGGER.debug("Closing websocket session {}", session);
                }
                session.close(NORMAL_CLOSE_CODE, reason);
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
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final WebSocketClient wsClient;

        public Factory() {
            this(new WebSocketClient());
        }

        public Factory(WebSocketClient wsClient) {
            this.wsClient = wsClient;
            addBean(wsClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new JettyWebSocketTransport(url, options, scheduler, wsClient);
        }
    }
}
