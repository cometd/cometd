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
package org.cometd.server.websocket.javax;

import java.util.List;
import java.util.concurrent.ExecutionException;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.websocket.common.AbstractWebSocketEndPoint;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketEndPoint extends Endpoint implements MessageHandler.Whole<String> {
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final AbstractWebSocketEndPoint _delegate;
    private volatile Session _wsSession;

    public WebSocketEndPoint(AbstractWebSocketTransport transport, BayeuxContext bayeuxContext) {
        _delegate = new Delegate(transport, bayeuxContext);
    }

    @Override
    public void onOpen(Session wsSession, EndpointConfig config) {
        _wsSession = wsSession;
        wsSession.addMessageHandler(this);
    }

    @Override
    public void onMessage(String data) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("WebSocket Text message on {}@{}",
                    getClass().getSimpleName(),
                    Integer.toHexString(hashCode()));
        }
        try {
            try {
                Promise.Completable<Void> completable = new Promise.Completable<>();
                _delegate.onMessage(data, completable);
                // Wait, to apply backpressure to the client.
                completable.get();
            } catch (ExecutionException x) {
                throw x.getCause();
            }
        } catch (Throwable failure) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("", failure);
            }
            _delegate.close(1011, failure.toString());
        }
    }

    @Override
    public void onClose(Session wsSession, CloseReason closeReason) {
        _delegate.onClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
    }

    @Override
    public void onError(Session wsSession, Throwable failure) {
        _delegate.onError(failure);
    }

    protected void writeComplete(AbstractWebSocketEndPoint.Context context, List<ServerMessage> messages) {
    }

    private class Delegate extends AbstractWebSocketEndPoint {
        public Delegate(AbstractWebSocketTransport transport, BayeuxContext bayeuxContext) {
            super(transport, bayeuxContext);
        }

        @Override
        protected void send(ServerSession session, String data, Callback callback) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Sending {}", data);
            }
            // Async write.
            _wsSession.getAsyncRemote().sendText(data, result -> {
                Throwable failure = result.getException();
                if (failure == null) {
                    callback.succeeded();
                } else {
                    callback.failed(failure);
                }
            });
        }

        @Override
        public void close(int code, String reason) {
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
        protected void writeComplete(Context context, List<ServerMessage> messages) {
            WebSocketEndPoint.this.writeComplete(context, messages);
        }
    }
}
