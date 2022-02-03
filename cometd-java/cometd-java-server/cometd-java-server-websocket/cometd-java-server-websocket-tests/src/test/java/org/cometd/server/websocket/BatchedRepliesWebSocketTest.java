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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BatchedRepliesWebSocketTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testBatchedReplies(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        AtomicReference<List<Message.Mutable>> batch = new AtomicReference<>();
        CountDownLatch repliesLatch = new CountDownLatch(1);
        ClientTransport transport;
        switch (wsType) {
            case WEBSOCKET_JSR356:
                transport = new org.cometd.client.websocket.javax.WebSocketTransport(null, null, null, wsClientContainer) {
                    @Override
                    protected WebSocketDelegate newDelegate() {
                        return new WebSocketDelegate() {
                            @Override
                            protected void onMessages(List<Message.Mutable> messages) {
                                super.onMessages(messages);
                                if (messages.size() > 1) {
                                    batch.set(messages);
                                    repliesLatch.countDown();
                                }
                            }
                        };
                    }
                };
                break;
            case WEBSOCKET_JETTY:
                transport = new org.cometd.client.websocket.jetty.JettyWebSocketTransport(null, null, null, wsClient) {
                    @Override
                    protected Delegate newDelegate() {
                        return new JettyWebSocketDelegate() {
                            @Override
                            protected void onMessages(List<Message.Mutable> messages) {
                                super.onMessages(messages);
                                if (messages.size() > 1) {
                                    batch.set(messages);
                                    repliesLatch.countDown();
                                }
                            }
                        };
                    }
                };
                break;
            case WEBSOCKET_OKHTTP:
                transport = new OkHttpWebSocketTransport(null, okHttpClient) {
                    @Override
                    protected OkHttpWebSocketTransport.OkHttpDelegate newDelegate() {
                        return new OkHttpDelegate() {
                            @Override
                            protected void onMessages(List<Message.Mutable> messages) {
                                super.onMessages(messages);
                                if (messages.size() > 1) {
                                    batch.set(messages);
                                    repliesLatch.countDown();
                                }
                            }
                        };
                    }
                };
                break;
            default:
                throw new IllegalArgumentException();
        }

        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/autobatch";
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown());
            channel.publish("data");
        });

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(repliesLatch.await(5, TimeUnit.SECONDS));
        List<Message.Mutable> messages = batch.get();
        Assertions.assertNotNull(messages);
        // List must contain subscribe reply and message reply
        Assertions.assertEquals(2, messages.size());
        // Replies must be in order.
        Assertions.assertEquals(Channel.META_SUBSCRIBE, messages.get(0).getChannel());
        Assertions.assertEquals(channelName, messages.get(1).getChannel());

        disconnectBayeuxClient(client);
    }
}
