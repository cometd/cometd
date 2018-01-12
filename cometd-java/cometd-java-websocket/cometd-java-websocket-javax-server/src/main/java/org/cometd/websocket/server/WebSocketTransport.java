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
package org.cometd.websocket.server;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.websocket.server.common.AbstractBayeuxContext;
import org.cometd.websocket.server.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.Callback;

public class WebSocketTransport extends AbstractWebSocketTransport<Session> {
    public WebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux);
    }

    @Override
    public void init() {
        super.init();

        final ServletContext context = (ServletContext)getOption(ServletContext.class.getName());
        if (context == null) {
            throw new IllegalArgumentException("Missing ServletContext");
        }

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING_OPTION);
        if (cometdURLMapping == null) {
            throw new IllegalArgumentException("Missing '" + COMETD_URL_MAPPING_OPTION + "' parameter");
        }

        ServerContainer container = (ServerContainer)context.getAttribute(ServerContainer.class.getName());
        if (container == null) {
            throw new IllegalArgumentException("Missing WebSocket ServerContainer");
        }

        // JSR 356 does not support a input buffer size option
        int maxMessageSize = getMaxMessageSize();
        if (maxMessageSize < 0) {
            maxMessageSize = container.getDefaultMaxTextMessageBufferSize();
        }
        container.setDefaultMaxTextMessageBufferSize(maxMessageSize);

        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, container.getDefaultMaxSessionIdleTimeout());
        container.setDefaultMaxSessionIdleTimeout(idleTimeout);

        String protocol = getProtocol();
        List<String> protocols = protocol == null ? null : Collections.singletonList(protocol);

        Configurator configurator = new Configurator(context);

        for (String mapping : normalizeURLMapping(cometdURLMapping)) {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(WebSocketScheduler.class, mapping)
                    .subprotocols(protocols)
                    .configurator(configurator)
                    .build();
            try {
                container.addEndpoint(config);
            } catch (DeploymentException x) {
                throw new RuntimeException(x);
            }
        }
    }

    protected boolean checkOrigin(String origin) {
        return true;
    }

    protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response) {
    }

    @Override
    protected void send(final Session wsSession, final ServerSession session, String data, final Callback callback) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Sending {}", data);
        }

        // Async write.
        wsSession.getAsyncRemote().sendText(data, new SendHandler() {
            @Override
            public void onResult(SendResult result) {
                Throwable failure = result.getException();
                if (failure == null) {
                    callback.succeeded();
                } else {
                    handleException(wsSession, session, failure);
                    callback.failed(failure);
                }
            }
        });
    }

    private class WebSocketScheduler extends Endpoint implements AbstractServerTransport.Scheduler, MessageHandler.Whole<String> {
        private final AbstractWebSocketScheduler delegate;
        private volatile Session _wsSession;

        private WebSocketScheduler(WebSocketContext context) {
            delegate = new AbstractWebSocketScheduler(context) {
                @Override
                protected void close(final int code, String reason) {
                    try {
                        // Limits of the WebSocket APIs, otherwise an exception is thrown.
                        reason = reason.substring(0, Math.min(reason.length(), 30));
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Closing {}/{}", code, reason);
                        }
                        _wsSession.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
                    } catch (Throwable x) {
                        _logger.trace("Could not close WebSocket session " + _wsSession, x);
                    }
                }

                @Override
                protected void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply) {
                    schedule(_wsSession, timeout, expiredConnectReply);
                }
            };
        }

        @Override
        public void onOpen(Session wsSession, EndpointConfig config) {
            _wsSession = wsSession;
            wsSession.addMessageHandler(this);
        }

        @Override
        public void onClose(Session wsSession, CloseReason closeReason) {
            delegate.onClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
        }

        @Override
        public void onError(Session wsSession, Throwable failure) {
            delegate.onError(failure);
        }

        @Override
        public void cancel() {
            delegate.cancel();
        }

        @Override
        public void schedule() {
            delegate.schedule();
        }

        @Override
        public void onMessage(String data) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("WebSocket Text message on {}@{}/{}@{}",
                        WebSocketTransport.this.getClass().getSimpleName(),
                        Integer.toHexString(WebSocketTransport.this.hashCode()),
                        getClass().getSimpleName(),
                        Integer.toHexString(hashCode()));
            }
            delegate.onMessage(_wsSession, data);
        }
    }

    private class WebSocketContext extends AbstractBayeuxContext {
        private WebSocketContext(ServletContext context, HandshakeRequest request, Map<String, Object> userProperties) {
            super(context, request.getRequestURI().toString(), request.getQueryString(), request.getHeaders(),
                    request.getParameterMap(), request.getUserPrincipal(), (HttpSession)request.getHttpSession(),
                    // Hopefully these will become a standard, for now they are Jetty specific.
                    (InetSocketAddress)userProperties.get("javax.websocket.endpoint.localAddress"),
                    (InetSocketAddress)userProperties.get("javax.websocket.endpoint.remoteAddress"),
                    retrieveLocales(userProperties));
        }
    }

    private static List<Locale> retrieveLocales(Map<String, Object> userProperties) {
        @SuppressWarnings("unchecked")
        List<Locale> locales = (List<Locale>)userProperties.get("javax.websocket.upgrade.locales");
        if (locales == null || locales.isEmpty()) {
            return Collections.singletonList(Locale.getDefault());
        }
        return locales;
    }

    private class Configurator extends ServerEndpointConfig.Configurator {
        private final ServletContext servletContext;

        private Configurator(ServletContext servletContext) {
            this.servletContext = servletContext;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            ContextHolder context = provideContext();
            context.bayeuxContext = new WebSocketContext(servletContext, request, sec.getUserProperties());
            WebSocketTransport.this.modifyHandshake(request, response);
        }

        @Override
        public boolean checkOrigin(String originHeaderValue) {
            return WebSocketTransport.this.checkOrigin(originHeaderValue);
        }

        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            ContextHolder context = provideContext();
            context.protocolMatches = checkProtocol(supported, requested);
            if (context.protocolMatches) {
                return super.getNegotiatedSubprotocol(supported, requested);
            }
            _logger.warn("Could not negotiate WebSocket SubProtocols: server{} != client{}", supported, requested);
            return null;
        }

        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
            List<Extension> negotiated = new ArrayList<>();
            for (Extension extension : requested) {
                String name = extension.getName();
                boolean option = getOption(ENABLE_EXTENSION_PREFIX_OPTION + name, true);
                if (option) {
                    negotiated.add(extension);
                }
            }
            return negotiated;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            ContextHolder context = provideContext();
            if (!getBayeux().getAllowedTransports().contains(getName())) {
                throw new InstantiationException("Transport not allowed");
            }
            if (!context.protocolMatches) {
                throw new InstantiationException("Could not negotiate WebSocket SubProtocols");
            }
            T instance = (T)new WebSocketScheduler(context.bayeuxContext);
            context.clear();
            return instance;
        }

        private ContextHolder provideContext() {
            ContextHolder result = ContextHolder.holder.get();
            if (result == null) {
                result = new ContextHolder();
                result.clear();
                ContextHolder.holder.set(result);
            }
            return result;
        }
    }

    private static class ContextHolder {
        private static final ThreadLocal<ContextHolder> holder = new ThreadLocal<>();
        private WebSocketContext bayeuxContext;
        private boolean protocolMatches;

        public void clear() {
            ContextHolder.holder.set(null);
            bayeuxContext = null;
            // Use a sensible default in case getNegotiatedSubprotocol() is not invoked.
            protocolMatches = true;
        }
    }
}
