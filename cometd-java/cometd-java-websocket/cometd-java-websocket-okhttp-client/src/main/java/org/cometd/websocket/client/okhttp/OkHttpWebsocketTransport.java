/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.websocket.client.okhttp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.cometd.bayeux.Message;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.websocket.client.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class OkHttpWebsocketTransport extends AbstractWebSocketTransport {
    // We specifically do not want to have a long blocking handshake, to allow for more request retries
    private static final String SEC_WEB_SOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final String SEC_WEB_SOCKET_ACCEPT_HEADER = "Sec-WebSocket-Accept";

    private final OkHttpClient okHttpClient;
    private boolean webSocketSupported;
    private boolean webSocketConnected;

    public OkHttpWebsocketTransport(Map<String, Object> options, OkHttpClient okHttpClient) {
        this(null, options, null, okHttpClient);
    }

    public OkHttpWebsocketTransport(String uri, Map<String, Object> options, ScheduledExecutorService scheduler, OkHttpClient okHttpClient) {
        super(uri, options, scheduler);
        OkHttpClient.Builder enrichedClient = okHttpClient.newBuilder()
                .connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS);
        if (okHttpClient.pingIntervalMillis() == 0) {
            enrichedClient.pingInterval(20, TimeUnit.SECONDS);
        }
        this.okHttpClient = enrichedClient.build();
        this.webSocketSupported = true;
    }

    @Override
    public void init() {
        super.init();
        this.webSocketSupported = true;
        this.webSocketConnected = false;
    }

    @Override
    public boolean accept(String s) {
        return webSocketSupported;
    }

    @Override
    protected Delegate connect(String uri, TransportListener listener, List<Message.Mutable> messages) {
        try {
            // We must make the okhttp call blocking for CometD to handshake properly.
            OkHttpDelegate delegate = newDelegate();
            Request upgradeRequest = buildUpgradeRequest(uri);
            okHttpClient.newWebSocket(upgradeRequest, delegate.listener);
            Throwable connectFailure = delegate.connectFuture.get(getConnectTimeout(), TimeUnit.MILLISECONDS);
            if (connectFailure != null) {
                throw connectFailure;
            }
            this.webSocketConnected = true;
            return delegate;
        } catch (ConnectException
                | SocketTimeoutException
                | TimeoutException
                | UnresolvedAddressException
                | ProtocolException e) { // RealWebSocket#checkResponse throws ProtocolException for certain responses
            listener.onFailure(e, messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onFailure(e, messages);
        } catch (Throwable e) {
            webSocketSupported = isStickyReconnect() && webSocketConnected;
            listener.onFailure(e, messages);
        }
        return null;
    }

    protected OkHttpDelegate newDelegate() {
        return new OkHttpDelegate();
    }

    private Request buildUpgradeRequest(String uri) {
        Request.Builder upgradeRequest = new Request.Builder();
        onHandshakeRequest(uri, upgradeRequest);
        return upgradeRequest.build();
    }

    protected void onHandshakeRequest(String uri, Request.Builder upgradeRequest) {
        upgradeRequest.url(uri);
        String protocol = getProtocol();
        if (protocol != null && !protocol.isEmpty()) {
            upgradeRequest.header(SEC_WEB_SOCKET_PROTOCOL_HEADER, protocol);
        }
        CookieStore cookieStore = getCookieStore();
        List<HttpCookie> cookies = cookieStore.get(URI.create(uri));
        for (HttpCookie cookie : cookies) {
            String cookieValue = cookie.getName() + "=" + cookie.getValue();
            upgradeRequest.addHeader(COOKIE_HEADER, cookieValue);
        }
    }

    protected void onHandshakeResponse(Response response) {
        webSocketSupported = response.header(SEC_WEB_SOCKET_ACCEPT_HEADER) != null;
        storeCookies(headersToMap(response.headers()));
    }

    public static Map<String, List<String>> headersToMap(Headers headers) {
        // We want to keep the header name case, so we cannot use Headers.toMultiMap().
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.names().forEach(name -> result.put(name, headers.values(name)));
        return result;
    }

    protected class OkHttpDelegate extends Delegate {
        private final WebSocketListener listener = new OkHttpListener();
        private final CompletableFuture<Throwable> connectFuture = new CompletableFuture<>();
        private WebSocket webSocket;

        public OkHttpDelegate() {
        }

        private void onOpen(WebSocket webSocket, Response response) {
            locked(() -> this.webSocket = webSocket);
            onHandshakeResponse(response);
            if (logger.isDebugEnabled()) {
                logger.debug("Opened {}", webSocket);
            }
        }

        @Override
        protected void send(String payload) {
            WebSocket webSocket = locked(() -> this.webSocket);
            try {
                if (webSocket == null) {
                    throw new IOException("Unconnected!");
                }
                boolean enqueued = webSocket.send(payload);
                if (!enqueued) {
                    throw new IOException("Not enqueued! Current queue size: " + webSocket.queueSize());
                }
            } catch (Throwable throwable) {
                logger.warn("Failure sending " + payload, throwable);
                fail(throwable, "Exception");
            }
        }

        @Override
        protected boolean isOpen() {
            return locked(() -> super.isOpen() && webSocket != null);
        }

        @Override
        protected void close() {
            locked(() -> webSocket = null);
        }

        @Override
        protected void shutdown(String reason) {
            WebSocket webSocket = locked(() -> {
                WebSocket result = this.webSocket;
                close();
                return result;
            });
            if (webSocket != null) {
                int code = NORMAL_CLOSE_CODE;
                if (logger.isDebugEnabled()) {
                    logger.debug("Closing websocket {}/{}", code, reason);
                }
                try {
                    reason = trimCloseReason(reason);
                    webSocket.close(code, reason);
                } catch (Throwable t) {
                    logger.warn(String.format("Unable to close websocket %d/%s", code, reason), t);
                }
            }
        }

        private final class OkHttpListener extends WebSocketListener {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                OkHttpDelegate.this.onOpen(webSocket, response);
                connectFuture.complete(null);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                OkHttpDelegate.this.onData(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                OkHttpDelegate.this.onClose(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
                if (!connectFuture.complete(failure)) {
                    OkHttpDelegate.this.fail(failure, "WebSocketListener Failure");
                }
            }
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final OkHttpClient okHttpClient;

        public Factory(OkHttpClient okHttpClient) {
            this.okHttpClient = okHttpClient;
            addBean(okHttpClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new OkHttpWebsocketTransport(url, options, scheduler, okHttpClient);
        }
    }
}
