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
package org.cometd.javascript;

import java.io.IOException;
import java.net.URI;

import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketConnection implements WebSocketListener {
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private JavaScript javaScript;
    private ScriptObjectMirror thiz;
    private WebSocketClient wsClient;
    private Session session;

    public WebSocketConnection(JavaScript javaScript, ScriptObjectMirror thiz, Object connector, String url, String protocol) {
        this.javaScript = javaScript;
        this.thiz = thiz;
        this.wsClient = ((WebSocketConnector)connector).getWebSocketClient();
        try {
            URI uri = new URI(url);

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            if (protocol != null) {
                request.setSubProtocols(protocol);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Opening WebSocket session to {}", uri);
            }
            wsClient.connect(this, uri, request);
        } catch (final Throwable x) {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(() -> onWebSocketError(x));
        }
    }

    public void send(String data) throws IOException {
        try {
            Session session = this.session;
            if (session != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("WebSocket sending data {}", data);
                }
                session.getRemote().sendString(data, null);
            }
        } catch (final Throwable x) {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(() -> onWebSocketError(x));
        }
    }

    public void close(int code, String reason) throws IOException {
        Session session = this.session;
        if (session != null) {
            session.close(code, reason);
            this.session = null;
        }
    }

    @Override
    public void onWebSocketConnect(Session session) {
        this.session = session;
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket opened session {}", session);
        }
        javaScript.invoke(false, thiz, "onopen");
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
    }

    @Override
    public void onWebSocketText(String data) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket message data {}", data);
        }
        // Use single quotes so they do not mess up with quotes in the data string
        Object event = javaScript.evaluate("event", "({data:'" + data + "'})");
        javaScript.invoke(false, thiz, "onmessage", event);
    }

    @Override
    public void onWebSocketClose(int closeCode, String reason) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket closed with code {}/{}", closeCode, reason);
        }
        // Use single quotes so they do not mess up with quotes in the reason string
        Object event = javaScript.evaluate("event", "({code:" + closeCode + ",reason:'" + reason + "'})");
        javaScript.invoke(false, thiz, "onclose", event);
    }

    @Override
    public void onWebSocketError(Throwable x) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket exception", x);
        }
        javaScript.invoke(false, thiz, "onerror");
    }
}
