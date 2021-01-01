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
package org.cometd.websocket;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.WebSocketContainer;
import okhttp3.OkHttpClient;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.TransportListener;
import org.cometd.websocket.client.JettyWebSocketTransport;
import org.cometd.websocket.client.WebSocketTransport;
import org.cometd.websocket.client.okhttp.OkHttpWebsocketTransport;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Assert;
import org.junit.Test;

public class ConcurrentAbortPublishTest extends ClientServerWebSocketTest {
    private boolean _abort;

    public ConcurrentAbortPublishTest(String wsTransportType) {
        super(wsTransportType);
    }

    @Override
    protected WebSocketTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new WebSocketTransport(url, options, null, wsContainer) {
            private final WebSocketTransport _transport = this;

            @Override
            protected WebSocketDelegate newDelegate() {
                return new WebSocketDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort) {
                            _transport.abort();
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (!_abort) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @Override
    protected JettyWebSocketTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new JettyWebSocketTransport(url, options, null, wsClient) {
            private final JettyWebSocketTransport _transport = this;

            @Override
            protected Delegate newDelegate() {
                return new JettyWebSocketDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort) {
                            _transport.abort();
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (!_abort) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @Override
    protected OkHttpWebsocketTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWebsocketTransport(url, options, null, okHttpClient) {
            private final OkHttpWebsocketTransport _transport = this;

            @Override
            protected OkHttpDelegate newDelegate() {
                return new OkHttpDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort) {
                            _transport.abort();
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (!_abort) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @Test
    public void testConcurrentAbortPublish() throws Exception {
        prepareAndStart(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        // Simulate abort() concurrent with publish().
        _abort = true;

        final CountDownLatch latch = new CountDownLatch(1);
        String data = "Hello World";
        client.getChannel("/test").publish(data, message -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
