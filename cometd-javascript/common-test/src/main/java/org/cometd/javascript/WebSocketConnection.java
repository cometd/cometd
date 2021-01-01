/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketConnection extends ScriptableObject implements WebSocketListener {
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private ThreadModel threads;
    private Scriptable thiz;
    private WebSocketClient wsClient;
    private Session session;

    public WebSocketConnection() {
    }

    public void jsConstructor(Object threadModel, Scriptable thiz, Object connector, String url, Object protocol) {
        this.threads = (ThreadModel)threadModel;
        this.thiz = thiz;
        this.wsClient = ((WebSocketConnector)connector).getWebSocketClient();
        try {
            URI uri = new URI(url);

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            if (protocol != null && protocol != Undefined.instance) {
                request.setSubProtocols(protocol.toString());
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Opening WebSocket session to {}", uri);
            }
            wsClient.connect(this, uri, request);
        } catch (final Throwable x) {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    onWebSocketError(x);
                }
            });
        }
    }

    @Override
    public String getClassName() {
        return "WebSocketConnection";
    }

    public void jsFunction_send(String data) throws IOException {
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
            wsClient.getExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    onWebSocketError(x);
                }
            });
        }
    }

    public void jsFunction_close(int code, String reason) throws IOException {
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
        threads.invoke(false, thiz, thiz, "onopen");
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
        Object event = threads.evaluate("event", "({data:'" + data + "'})");
        threads.invoke(false, thiz, thiz, "onmessage", event);
    }

    @Override
    public void onWebSocketClose(int closeCode, String reason) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket closed with code {}/{}", closeCode, reason);
        }
        // Use single quotes so they do not mess up with quotes in the reason string
        Object event = threads.evaluate("event", "({code:" + closeCode + ",reason:'" + reason + "'})");
        threads.invoke(false, thiz, thiz, "onclose", event);
    }

    @Override
    public void onWebSocketError(Throwable x) {
        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket exception", x);
        }
        threads.invoke(false, thiz, thiz, "onerror");
    }
}
