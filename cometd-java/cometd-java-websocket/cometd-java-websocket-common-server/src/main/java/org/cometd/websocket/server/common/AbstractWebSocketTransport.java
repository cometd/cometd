/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.websocket.server.common;

import java.util.ArrayList;
import java.util.List;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;

public abstract class AbstractWebSocketTransport extends AbstractServerTransport {
    public static final String NAME = "websocket";
    public static final String PREFIX = "ws";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String MESSAGES_PER_FRAME_OPTION = "messagesPerFrame";
    public static final String BUFFER_SIZE_OPTION = "bufferSize";
    public static final String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public static final String COMETD_URL_MAPPING_OPTION = "cometdURLMapping";
    public static final String REQUIRE_HANDSHAKE_PER_CONNECTION_OPTION = "requireHandshakePerConnection";
    public static final String ENABLE_EXTENSION_PREFIX_OPTION = "enableExtension.";

    private final ThreadLocal<BayeuxContext> _bayeuxContext = new ThreadLocal<>();
    private String _protocol;
    private int _messagesPerFrame;
    private boolean _requireHandshakePerConnection;

    protected AbstractWebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        _protocol = getOption(PROTOCOL_OPTION, null);
        _messagesPerFrame = getOption(MESSAGES_PER_FRAME_OPTION, 1);
        _requireHandshakePerConnection = getOption(REQUIRE_HANDSHAKE_PER_CONNECTION_OPTION, false);
    }

    public String getProtocol() {
        return _protocol;
    }

    public int getMessagesPerFrame() {
        return _messagesPerFrame;
    }

    public boolean isRequireHandshakePerConnection() {
        return _requireHandshakePerConnection;
    }

    @Override
    public BayeuxContext getContext() {
        return _bayeuxContext.get();
    }

    protected void setContext(BayeuxContext context) {
        _bayeuxContext.set(context);
    }

    protected List<String> normalizeURLMapping(String urlMapping) {
        String[] mappings = urlMapping.split(",");
        List<String> result = new ArrayList<>(mappings.length);
        for (String mapping : mappings) {
            if (mapping.endsWith("/*")) {
                mapping = mapping.substring(0, mapping.length() - 2);
            }
            if (!mapping.startsWith("/")) {
                mapping = "/" + mapping;
            }
            result.add(mapping);
        }
        return result;
    }

    protected void onClose(int code, String reason) {
    }
}
