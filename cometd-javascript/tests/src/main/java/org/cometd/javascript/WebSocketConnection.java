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
package org.cometd.javascript;

import java.net.URI;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This class is the underlying implementation of JavaScript's {@code window.WebSocket} in {@code browser.js}.</p>
 */
public class WebSocketConnection implements WebSocketListener {
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private JavaScript javaScript;
    private Object jsWebSocket;
    private WebSocketClient wsClient;
    private Session session;

    /**
     * <p>This constructor is invoked from {@code browser.js},
     * from the {@code window.WebSocket} constructor.</p>
     *
     * @param javaScript the (java) JavaScript object
     * @param jsWebSocket the (javascript) WebSocket object created in {@code browser.js}
     * @param connector the (java) WebSocketConnector object
     * @param url the WebSocket URL passed to the {@code window.WebSocket(url, protocol)} constructor
     * @param protocol the WebSocket protocol passed to the {@code window.WebSocket(url, protocol)} constructor
     */
    public WebSocketConnection(JavaScript javaScript, Object jsWebSocket, Object connector, String url, String protocol) {
        this.javaScript = javaScript;
        this.jsWebSocket = jsWebSocket;
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
        } catch (Throwable x) {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(() -> onWebSocketError(x));
        }
    }

    /**
     * <p>This method is invoked from {@code browser.js},
     * from the {@code window.WebSocket.send(data)} function.</p>
     *
     * @param data the data to send
     */
    public void send(String data) {
        try {
            Session session = this.session;
            if (session != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("WebSocket sending data {}", data);
                }
                session.getRemote().sendString(data, null);
            }
        } catch (Throwable x) {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(() -> onWebSocketError(x));
        }
    }

    /**
     * <p>This method is invoked from {@code browser.js},
     * from the {@code window.WebSocket.close(code, reason)} function.</p>
     *
     * @param code the close code
     * @param reason the close reason
     */
    public void close(int code, String reason) {
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
        // This method may be invoked from JavaScript (from new window.WebSocket()),
        // but we want to execute the onopen() function asynchronously, otherwise
        // the onopen() function may not be assigned yet in cometd.js.
        wsClient.getExecutor().execute(() -> javaScript.invoke(false, jsWebSocket, "onopen"));
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
        javaScript.invoke(false, jsWebSocket, "onmessage", event);
    }

    @Override
    public void onWebSocketClose(int closeCode, String reason) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket closed with code {}/{}", closeCode, reason);
        }
        // Use single quotes so they do not mess up with quotes in the reason string
        Object event = javaScript.evaluate("event", "({code:" + closeCode + ",reason:'" + reason + "'})");
        javaScript.invoke(false, jsWebSocket, "onclose", event);
    }

    @Override
    public void onWebSocketError(Throwable x) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket exception", x);
        }
        javaScript.invoke(false, jsWebSocket, "onerror");
    }
}
