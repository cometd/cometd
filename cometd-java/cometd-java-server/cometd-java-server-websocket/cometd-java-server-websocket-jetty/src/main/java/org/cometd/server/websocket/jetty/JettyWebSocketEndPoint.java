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
package org.cometd.server.websocket.jetty;

import java.util.concurrent.ExecutionException;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.websocket.common.AbstractWebSocketEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
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
            try {
                Promise.Completable<Void> completable = new Promise.Completable<>();
                onMessage(data, completable);
                completable.get();
            } catch (ExecutionException x) {
                throw x.getCause();
            }
        } catch (Throwable failure) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("", failure);
            }
            close(1011, failure.toString());
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
            _logger.debug("Sending {}", data);
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
            _logger.debug("Closing {}/{}", code, reason);
        }
        _wsSession.close(code, reason);
    }
}
