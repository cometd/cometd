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
package org.cometd.websocket.server;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Test;

public class BatchedRepliesWebSocketTest extends ClientServerWebSocketTest {
    public BatchedRepliesWebSocketTest(String wsTransportType) {
        super(wsTransportType);
    }

    @Test
    public void testBatchedReplies() throws Exception {
        prepareAndStart(null);

        final AtomicReference<List<Message.Mutable>> batch = new AtomicReference<>();
        final CountDownLatch repliesLatch = new CountDownLatch(1);
        ClientTransport transport;
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                transport = new org.cometd.websocket.client.WebSocketTransport(null, null, null, wsClientContainer) {
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
                transport = new org.cometd.websocket.client.JettyWebSocketTransport(null, null, null, wsClient) {
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
            default:
                throw new IllegalArgumentException();
        }

        final BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final String channelName = "/autobatch";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        client.batch(new Runnable() {
            @Override
            public void run() {
                ClientSessionChannel channel = client.getChannel(channelName);
                channel.subscribe(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        messageLatch.countDown();
                    }
                });
                channel.publish("data");
            }
        });

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(repliesLatch.await(5, TimeUnit.SECONDS));
        List<Message.Mutable> messages = batch.get();
        Assert.assertNotNull(messages);
        // List must contain subscribe reply and message reply
        Assert.assertEquals(2, messages.size());
        // Replies must be in order.
        Assert.assertEquals(Channel.META_SUBSCRIBE, messages.get(0).getChannel());
        Assert.assertEquals(channelName, messages.get(1).getChannel());

        disconnectBayeuxClient(client);
    }
}
