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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.websocket.common.AbstractBayeuxContext;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.websocket.api.ExtensionConfig;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
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

        Context context = (Context)getOption(Context.class.getName());
        if (context == null) {
            throw new IllegalArgumentException("Missing Context");
        }

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING_OPTION);
        if (cometdURLMapping == null) {
            throw new IllegalArgumentException("Missing '" + COMETD_URL_MAPPING_OPTION + "' parameter");
        }

        ServerWebSocketContainer container = ServerWebSocketContainer.get(context);
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
            container.addMapping(mapping, (request, response, callback) -> {
                String origin = request.getHeaders().get("Origin");
                if (origin == null) {
                    origin = request.getHeaders().get("Sec-WebSocket-Origin");
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
                        JettyWebSocketContext handshake = new JettyWebSocketContext(request);
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

    protected void modifyUpgrade(ServerUpgradeRequest request, ServerUpgradeResponse response) {
    }

    protected boolean checkOrigin(ServerUpgradeRequest request, String origin) {
        return true;
    }

    private static class JettyWebSocketContext extends AbstractBayeuxContext {
        private final Map<String, Object> contextAttributes;
        private final Map<String, Object> requestAttributes;
        private final Map<String, Object> sessionAttributes;

        private JettyWebSocketContext(ServerUpgradeRequest request) {
            super(request.getHttpURI().toString(), request.getContext().getContextPath(), null, headersToMap(request),
                    queryToMap(request), /*TODO*/null,
                    request.getConnectionMetaData().getLocalSocketAddress(), request.getConnectionMetaData().getRemoteSocketAddress(),
                    Request.getLocales(request), "HTTP/1.1", request.isSecure());
            this.contextAttributes = new LinkedHashMap<>(request.getContext().asAttributeMap());
            this.requestAttributes = new LinkedHashMap<>(request.asAttributeMap());
            Session session = request.getSession(false);
            this.sessionAttributes = session == null ? Map.of() : new LinkedHashMap<>(session.asAttributeMap());
        }

        private static Map<String, List<String>> headersToMap(ServerUpgradeRequest request) {
            HttpFields headers = request.getHeaders();
            Map<String, List<String>> result = new LinkedHashMap<>();
            headers.forEach(field -> {
                String name = field.getName();
                result.compute(name, (k, v) -> {
                    if (v == null) {
                        v = new ArrayList<>(1);
                    }
                    v.addAll(field.getValueList());
                    return v;
                });
            });
            return result;
        }

        private static Map<String, List<String>> queryToMap(ServerUpgradeRequest request) {
            Fields fields = Request.extractQueryParameters(request);
            Map<String, List<String>> result = new LinkedHashMap<>();
            fields.forEach(field -> {
                String name = field.getName();
                result.compute(name, (k, v) -> {
                    if (v == null) {
                        v = new ArrayList<>(1);
                    }
                    v.addAll(field.getValues());
                    return v;
                });
            });
            return result;
        }

        @Override
        public Object getContextAttribute(String name) {
            return contextAttributes.get(name);
        }

        @Override
        public Object getRequestAttribute(String name) {
            return requestAttributes.get(name);
        }

        @Override
        public Object getSessionAttribute(String name) {
            return sessionAttributes.get(name);
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
