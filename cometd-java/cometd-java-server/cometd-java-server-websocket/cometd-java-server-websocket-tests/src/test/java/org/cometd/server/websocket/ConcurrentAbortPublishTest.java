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
package org.cometd.server.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConcurrentAbortPublishTest extends ClientServerWebSocketTest {
    private Throwable _abort;

    @Override
    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new WebSocketTransport(url, options, null, wsContainer) {
            private final WebSocketTransport _transport = this;

            @Override
            protected WebSocketDelegate newDelegate() {
                return new WebSocketDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort != null) {
                            _transport.abort(_abort);
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (_abort == null) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @Override
    protected ClientTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new JettyWebSocketTransport(url, options, null, wsClient) {
            private final JettyWebSocketTransport _transport = this;

            @Override
            protected Delegate newDelegate() {
                return new JettyWebSocketDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort != null) {
                            _transport.abort(_abort);
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (_abort == null) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @Override
    protected ClientTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWebSocketTransport(url, options, null, okHttpClient) {
            private final OkHttpWebSocketTransport _transport = this;

            @Override
            protected OkHttpDelegate newDelegate() {
                return new OkHttpDelegate() {
                    @Override
                    protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                        if (_abort != null) {
                            _transport.abort(_abort);
                        }
                        super.registerMessages(listener, messages);
                    }

                    @Override
                    protected void shutdown(String reason) {
                        // If we are aborting from the test,
                        // skip the shutdown to simulate concurrency.
                        if (_abort == null) {
                            super.shutdown(reason);
                        }
                    }
                };
            }
        };
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testConcurrentAbortPublish(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        // Simulate abort() concurrent with publish().
        _abort = new IOException("aborted");

        CountDownLatch latch = new CountDownLatch(1);
        String data = "Hello World";
        client.getChannel("/test").publish(data, message -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
