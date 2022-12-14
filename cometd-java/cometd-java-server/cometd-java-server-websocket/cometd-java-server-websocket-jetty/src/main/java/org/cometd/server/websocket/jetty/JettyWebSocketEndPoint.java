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

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.websocket.common.AbstractWebSocketEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketEndPoint extends AbstractWebSocketEndPoint implements WebSocketListener {
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private volatile Session _wsSession;

    public JettyWebSocketEndPoint(JettyWebSocketTransport transport, BayeuxContext context) {
        super(transport, context);
    }

    @Override
    public void onWebSocketConnect(Session session) {
        _wsSession = session;
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
    }

    @Override
    public void onWebSocketText(String data) {
        try {
            SuspendToken suspendToken = _wsSession.suspend();
            onMessage(data, Promise.from(v -> suspendToken.resume(), this::handleFailure));
        } catch (Throwable failure) {
            handleFailure(failure);
        }
    }

    @Override
    public void onWebSocketClose(int code, String reason) {
        onClose(code, reason);
    }

    @Override
    public void onWebSocketError(Throwable failure) {
        onError(failure);
    }

    @Override
    protected void send(ServerSession session, String data, Callback callback) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Sending {} on {}", data, this);
        }

        // Async version.
        _wsSession.getRemote().sendString(data, new WriteCallback() {
            @Override
            public void writeSuccess() {
                callback.succeeded();
            }

            @Override
            public void writeFailed(Throwable x) {
                callback.failed(x);
            }
        });
    }

    @Override
    public void close(int code, String reason) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Closing {}/{} on {}", code, reason, this);
        }
        _wsSession.close(code, reason);
    }

    private void handleFailure(Throwable t)
    {
        if (_logger.isDebugEnabled()) {
            _logger.debug("", t);
        }
        close(1011, t.toString());
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", super.toString(), _wsSession);
    }
}
