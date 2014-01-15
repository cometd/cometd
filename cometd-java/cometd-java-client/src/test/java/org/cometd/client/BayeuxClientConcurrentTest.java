/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.LongPollingTransport;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BayeuxClientConcurrentTest extends ClientServerTest
{
    @Before
    public void startServer() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testHandshakeFailsConcurrentDisconnect() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected boolean sendHandshake()
            {
                disconnect();
                assertFalse(super.sendHandshake());
                latch.countDown();
                return false;
            }
        };
        client.handshake();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
    }

    @Test
    public void testConnectFailsConcurrentDisconnect() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected boolean sendConnect()
            {
                disconnect();
                assertFalse(super.sendConnect());
                latch.countDown();
                return false;
            }
        };
        client.handshake();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertTrue(client.isDisconnected());
    }

    @Test
    public void testSubscribeFailsConcurrentDisconnect() throws Exception
    {
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected void enqueueSend(Message.Mutable message)
            {
                disconnect();
                assertTrue(waitFor(5000, State.DISCONNECTED));
                super.enqueueSend(message);
            }
        };
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertFalse(message.isSuccessful());
                latch.countDown();
            }
        });
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        client.getChannel("/test").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentDisconnect() throws Exception
    {
        final String channelName = "/test";
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected void enqueueSend(Message.Mutable message)
            {
                if (channelName.equals(message.getChannel()))
                {
                    disconnect();
                    assertTrue(waitFor(5000, State.DISCONNECTED));
                }
                super.enqueueSend(message);
            }
        };
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertTrue(message.isSuccessful());
                latch.countDown();
            }
        });
        final CountDownLatch failLatch = new CountDownLatch(1);
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertFalse(message.isSuccessful());
                failLatch.countDown();
            }
        });
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch publishLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                publishLatch.countDown();
            }
        });

        // Wait to be subscribed
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Publish will fail
        channel.publish(new HashMap<>());
        assertTrue(failLatch.await(5, TimeUnit.SECONDS));
        assertFalse(publishLatch.await(1, TimeUnit.SECONDS));

        assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentNetworkDown() throws Exception
    {
        final String channelName = "/test";
        final AtomicInteger connects = new AtomicInteger();
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected boolean sendConnect()
            {
                try
                {
                    if (connects.incrementAndGet() == 2)
                        server.stop();
                    return super.sendConnect();
                }
                catch (Exception x)
                {
                    return false;
                }
            }
        };
        final CountDownLatch publishLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.countDown();
            }
        });
        client.handshake();

        assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        channel.publish(new HashMap<>());
        assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeListenersAreNotifiedBeforeConnectListeners() throws Exception
    {
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
        final int sleep = 1000;
        final AtomicBoolean handshaken = new AtomicBoolean();
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                try
                {
                    Thread.sleep(sleep);
                    handshaken.set(true);
                }
                catch (InterruptedException x)
                {
                    // Ignored
                }
            }
        });
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (handshaken.get())
                    connectLatch.countDown();
            }
        });
        client.handshake();

        assertTrue(connectLatch.await(2 * sleep, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testConcurrentHandshakeAndBatch() throws Exception
    {
        final CountDownLatch sendLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            protected boolean sendMessages(List<Message.Mutable> messages)
            {
                sendLatch.countDown();
                return super.sendMessages(messages);
            }
        };

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                try
                {
                    handshakeLatch.await();
                }
                catch (InterruptedException x)
                {
                    // Ignored
                }
            }
        });

        client.handshake();

        final CountDownLatch messageLatch = new CountDownLatch(1);
        client.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel channel = client.getChannel("/foobar");
                channel.subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messageLatch.countDown();
                    }
                });

                // Allow handshake to complete so that sendBatch() is triggered
                handshakeLatch.countDown();

                try
                {
                    // Be sure messages are not sent (we're still batching)
                    assertFalse(sendLatch.await(1, TimeUnit.SECONDS));
                }
                catch (InterruptedException x)
                {
                    // Ignored
                }

                channel.publish("DATA");
            }
        });

        assertTrue(sendLatch.await(5, TimeUnit.SECONDS));
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
