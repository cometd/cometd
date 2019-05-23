/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientConcurrentTest extends ClientServerTest {
    @Before
    public void startServer() throws Exception {
        start(null);
    }

    @Test
    public void testHandshakeFailsConcurrentDisconnect() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void sendHandshake() {
                disconnect();
                super.sendHandshake();
                latch.countDown();
            }
        };
        client.handshake();
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testConnectFailsConcurrentDisconnect() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void sendConnect() {
                disconnect();
                super.sendConnect();
                latch.countDown();
            }
        };
        client.handshake();
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testSubscribeFailsConcurrentDisconnect() throws Exception {
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void enqueueSend(Message.Mutable message) {
                disconnect();
                Assert.assertTrue(waitFor(5000, State.DISCONNECTED));
                super.enqueueSend(message);
            }
        };
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assert.assertFalse(message.isSuccessful());
            latch.countDown();
        });
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        client.getChannel("/test").subscribe((channel, message) -> {
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentDisconnect() throws Exception {
        final String channelName = "/test";
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void enqueueSend(Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    disconnect();
                    Assert.assertTrue(waitFor(5000, State.DISCONNECTED));
                }
                super.enqueueSend(message);
            }
        };
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assert.assertTrue(message.isSuccessful());
            latch.countDown();
        });
        final CountDownLatch failLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            Assert.assertFalse(m.isSuccessful());
            failLatch.countDown();
        });
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch publishLatch = new CountDownLatch(1);
        channel.subscribe((c, m) -> publishLatch.countDown());

        // Wait to be subscribed
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Publish will fail
        channel.publish(new HashMap<>());
        Assert.assertTrue(failLatch.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(publishLatch.await(1, TimeUnit.SECONDS));

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentNetworkDown() throws Exception {
        final String channelName = "/test";
        final AtomicInteger connects = new AtomicInteger();
        final BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void sendConnect() {
                try {
                    if (connects.incrementAndGet() == 2) {
                        server.stop();
                    }
                    super.sendConnect();
                } catch (Exception x) {
                    x.printStackTrace();
                }
            }
        };
        final CountDownLatch publishLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (!m.isSuccessful()) {
                publishLatch.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        channel.publish(new HashMap<>());
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeListenersAreNotifiedBeforeConnectListeners() throws Exception {
        final BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient));
        final int sleep = 1000;
        final AtomicBoolean handshaken = new AtomicBoolean();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            try {
                Thread.sleep(sleep);
                handshaken.set(true);
            } catch (InterruptedException x) {
                // Ignored
            }
        });
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (handshaken.get()) {
                connectLatch.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(connectLatch.await(2 * sleep, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testConcurrentHandshakeAndBatch() throws Exception {
        final String channelName = "/foobar";
        final CountDownLatch sendLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void sendMessages(List<Message.Mutable> messages, Promise<Boolean> promise) {
                for (Message message : messages) {
                    if (message.getChannel().equals(channelName)) {
                        sendLatch.countDown();
                    }
                }
                super.sendMessages(messages, promise);
            }
        };

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            try {
                handshakeLatch.await();
            } catch (InterruptedException x) {
                // Ignored
            }
        });

        client.handshake();

        final CountDownLatch messageLatch = new CountDownLatch(1);
        client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown());

            // Allow handshake to complete so that sendBatch() is triggered
            handshakeLatch.countDown();

            try {
                // Be sure messages are not sent (we're still batching)
                Assert.assertFalse(sendLatch.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException x) {
                // Ignored
            }

            channel.publish("DATA");
        });

        Assert.assertTrue(sendLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
