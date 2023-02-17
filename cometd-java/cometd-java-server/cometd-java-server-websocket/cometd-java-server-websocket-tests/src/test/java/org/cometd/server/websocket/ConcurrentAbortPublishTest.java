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
package org.cometd.server.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.jakarta.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConcurrentAbortPublishTest extends ClientServerWebSocketTest {
    private Throwable _abort;

    @Override
    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new WSTransport(url, options, wsContainer);
    }

    @Override
    protected ClientTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new JettyWSTransport(url, options, wsClient);
    }

    @Override
    protected ClientTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWSTransport(url, options, okHttpClient);
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

    public class WSTransport extends WebSocketTransport {
        public WSTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
            super(url, options, null, wsContainer);
        }

        @Override
        protected WebSocketDelegate newDelegate() {
            return new WSDelegate();
        }

        public class WSDelegate extends WebSocketDelegate {
            @Override
            protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                if (_abort != null) {
                    abort(_abort);
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
        }
    }

    public class JettyWSTransport extends JettyWebSocketTransport {
        public JettyWSTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
            super(url, options, null, wsClient);
        }

        @Override
        protected Delegate newDelegate() {
            return new JettyWSDelegate();
        }

        public class JettyWSDelegate extends JettyWebSocketTransport.JettyWebSocketDelegate {
            @Override
            protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                if (_abort != null) {
                    abort(_abort);
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
        }
    }

    public class OkHttpWSTransport extends OkHttpWebSocketTransport {
        public OkHttpWSTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
            super(url, options, null, okHttpClient);
        }

        @Override
        protected OkHttpDelegate newDelegate() {
            return new OkHttpDelegate();
        }

        public class OkHttpDelegate extends OkHttpWebSocketTransport.OkHttpDelegate {
            @Override
            protected void registerMessages(TransportListener listener, List<Message.Mutable> messages) {
                if (_abort != null) {
                    abort(_abort);
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
}
