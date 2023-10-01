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
package org.cometd.server.websocket.jetty;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.websocket.common.AbstractBayeuxContext;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketTransport extends AbstractWebSocketTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(JettyWebSocketTransport.class);

    public JettyWebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux);
    }

    @Override
    public void init() {
        super.init();

        ServletContext context = (ServletContext)getOption(ServletContext.class.getName());
        if (context == null) {
            throw new IllegalArgumentException("Missing ServletContext");
        }

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING_OPTION);
        if (cometdURLMapping == null) {
            throw new IllegalArgumentException("Missing '" + COMETD_URL_MAPPING_OPTION + "' parameter");
        }

        JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(context);
        if (container == null) {
            throw new IllegalArgumentException("Missing JettyWebSocketServerContainer");
        }

        int bufferSize = getOption(BUFFER_SIZE_OPTION, container.getInputBufferSize());
        container.setInputBufferSize(bufferSize);
        long maxMessageSize = getMaxMessageSize();
        if (maxMessageSize < 0) {
            maxMessageSize = container.getMaxTextMessageSize();
        }
        container.setMaxTextMessageSize(maxMessageSize);

        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, container.getIdleTimeout().toMillis());
        container.setIdleTimeout(Duration.ofMillis(idleTimeout));

        for (String mapping : normalizeURLMapping(cometdURLMapping)) {
            container.addMapping(mapping, (request, response) -> {
                String origin = request.getHeader("Origin");
                if (origin == null) {
                    origin = request.getHeader("Sec-WebSocket-Origin");
                }
                if (checkOrigin(request, origin)) {
                    List<ExtensionConfig> negotiated = new ArrayList<>();
                    for (ExtensionConfig extensionConfig : request.getExtensions()) {
                        String name = extensionConfig.getName();
                        boolean option = getOption(ENABLE_EXTENSION_PREFIX_OPTION + name, true);
                        if (option) {
                            negotiated.add(extensionConfig);
                        }
                    }
                    response.setExtensions(negotiated);

                    modifyUpgrade(request, response);

                    List<String> allowedTransports = getBayeux().getAllowedTransports();
                    if (allowedTransports.contains(getName())) {
                        WebSocketContext handshake = new WebSocketContext(context, request);
                        Object instance = newWebSocketEndPoint(handshake);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Created {}", instance);
                        }
                        return instance;
                    } else {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Transport not those allowed: {}", allowedTransports);
                        }
                    }
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Origin check failed for origin {}", origin);
                    }
                }
                return null;
            });
        }
    }

    protected Object newWebSocketEndPoint(BayeuxContext bayeuxContext) {
        return new EndPoint(bayeuxContext);
    }

    protected void modifyUpgrade(JettyServerUpgradeRequest request, JettyServerUpgradeResponse response) {
    }

    protected boolean checkOrigin(JettyServerUpgradeRequest request, String origin) {
        return true;
    }

    private static class WebSocketContext extends AbstractBayeuxContext {
        private final Map<String, Object> attributes;

        private WebSocketContext(ServletContext context, JettyServerUpgradeRequest request) {
            super(context, request.getRequestURI().toString(), request.getQueryString(), request.getHeaders(),
                    request.getParameterMap(), request.getUserPrincipal(), getHttpSession(request),
                    (InetSocketAddress)request.getLocalSocketAddress(), (InetSocketAddress)request.getRemoteSocketAddress(),
                    Collections.list(request.getLocales()), "HTTP/1.1", request.isSecure());
            this.attributes = request.getServletAttributes();
        }

        @Override
        public Object getRequestAttribute(String name) {
            return attributes.get(name);
        }

        private static HttpSession getHttpSession(JettyServerUpgradeRequest request) {
            try {
                return (HttpSession)request.getSession();
            } catch (Throwable x) {
                return null;
            }
        }
    }

    public class EndPoint extends JettyWebSocketEndPoint {
        public EndPoint(BayeuxContext bayeuxContext) {
            super(JettyWebSocketTransport.this, bayeuxContext);
        }

        @Override
        protected void writeComplete(Context context, List<ServerMessage> messages) {
            JettyWebSocketTransport.this.writeComplete(context, messages);
        }
    }
}
