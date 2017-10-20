/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientExtensionTest extends ClientServerTest {
    @Before
    public void init() throws Exception {
        startServer(null);
    }

    @Test
    public void testHandshake() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountingExtension extension = new CountingExtension(Channel.META_HANDSHAKE);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testConnect() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountingExtension extension = new CountingExtension(Channel.META_CONNECT);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second connect to be sent
        Thread.sleep(1000);

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(2, extension.sendMetas.size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscribe() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountingExtension extension = new CountingExtension(Channel.META_SUBSCRIBE);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                latch.countDown();
            }
        });
        client.getChannel("/foo").subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testUnsubscribe() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountingExtension extension = new CountingExtension(Channel.META_UNSUBSCRIBE);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_UNSUBSCRIBE).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                latch.countDown();
            }
        });
        final ClientSessionChannel channel = client.getChannel("/foo");
        final ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
            }
        };
        client.batch(new Runnable() {
            @Override
            public void run() {
                channel.subscribe(listener);
                channel.unsubscribe(listener);
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublish() throws Exception {
        BayeuxClient client = newBayeuxClient();
        String channelName = "/test";
        CountingExtension extension = new CountingExtension(channelName);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final ClientSessionChannel channel = client.getChannel(channelName);
        client.batch(new Runnable() {
            @Override
            public void run() {
                channel.subscribe(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                    }
                });
                channel.publish(new HashMap<>());
            }
        });

        // Wait for the message to arrive, along with the publish response
        Thread.sleep(1000);

        Assert.assertEquals(2, extension.rcvs.size());
        Assert.assertEquals(0, extension.rcvMetas.size());
        Assert.assertEquals(1, extension.sends.size());
        Assert.assertEquals(0, extension.sendMetas.size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDisconnect() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountingExtension extension = new CountingExtension(Channel.META_DISCONNECT);
        client.addExtension(extension);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second connect to be sent
        Thread.sleep(1000);

        disconnectBayeuxClient(client);

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());
    }

    @Test
    public void testReturningFalseOnSend() throws Exception {
        String channelName = "/test";
        final CountDownLatch latch = new CountDownLatch(1);
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                return false;
            }
        });
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        client.getChannel(channelName).publish(new HashMap<>());

        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testExtensionIsInvokedAfterNetworkFailure() throws Exception {
        final BayeuxClient client = newBayeuxClient();
        final String channelName = "/test";
        final AtomicReference<CountDownLatch> rcv = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        client.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean rcv(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    rcv.get().countDown();
                }
                return true;
            }

            @Override
            public boolean rcvMeta(ClientSession session, Message.Mutable message) {
                return true;
            }
        });
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                    }
                }, new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        if (message.isSuccessful()) {
                            subscribeLatch.countDown();
                        }
                    }
                });
            }
        });
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // This message will be delivered via /meta/connect.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data1");
        Assert.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));
        // Wait for the /meta/connect to be established again.
        Thread.sleep(1000);

        httpClient.stop();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        rcv.set(new CountDownLatch(1));
        httpClient.start();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // This message will be delivered via /meta/connect.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data2");
        Assert.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private class CountingExtension implements ClientSession.Extension {
        private final List<Message> rcvs = new ArrayList<>();
        private final List<Message> rcvMetas = new ArrayList<>();
        private final List<Message> sends = new ArrayList<>();
        private final List<Message> sendMetas = new ArrayList<>();
        private final String channel;

        private CountingExtension(String channel) {
            this.channel = channel;
        }

        @Override
        public boolean rcv(ClientSession session, Message.Mutable message) {
            if (ChannelId.isMeta(channel) || channel.equals(message.getChannel())) {
                rcvs.add(message);
            }
            return true;
        }

        @Override
        public boolean rcvMeta(ClientSession session, Message.Mutable message) {
            if (channel.equals(message.getChannel())) {
                rcvMetas.add(message);
            }
            return true;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            if (ChannelId.isMeta(channel) || channel.equals(message.getChannel())) {
                sends.add(message);
            }
            return true;
        }

        @Override
        public boolean sendMeta(ClientSession session, Message.Mutable message) {
            if (channel.equals(message.getChannel())) {
                sendMetas.add(message);
            }
            return true;
        }
    }
}
