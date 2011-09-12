/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.BayeuxClient.State;
import org.cometd.common.HashMapMessage;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientTest extends ClientServerWebSocketTest
{
    @Before
    public void setUp() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testBatchingAfterHandshake() throws Exception
    {
        final BayeuxClient client = newBayeuxClient();
        final AtomicBoolean connected = new AtomicBoolean();
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
            }
        });
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
            }
        });
        client.handshake();

        final String channelName = "/foo/bar";
        final BlockingArrayQueue<String> messages = new BlockingArrayQueue<String>();
        client.batch(new Runnable()
        {
            public void run()
            {
                // Subscribe and publish must be batched so that they are sent in order,
                // otherwise it's possible that the subscribe arrives to the server after the publish
                client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messages.add(channel.getId());
                        messages.add(message.getData().toString());
                    }
                });
                client.getChannel(channelName).publish("hello");
            }
        });

        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(channelName, messages.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals("hello", messages.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeDenied() throws Exception
    {
        BayeuxClient client = newBayeuxClient();
        SecurityPolicy oldPolicy = bayeux.getSecurityPolicy();
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                return false;
            }
        });
        try
        {
            final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
            client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    Assert.assertFalse(message.isSuccessful());
                    latch.get().countDown();
                }
            });
            client.handshake();
            Assert.assertTrue(latch.get().await(1000, TimeUnit.MILLISECONDS));

            // Be sure it does not retry
            latch.set(new CountDownLatch(1));
            Assert.assertFalse(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

            Assert.assertTrue(client.waitFor(1000, State.DISCONNECTED));
        }
        finally
        {
            bayeux.setSecurityPolicy(oldPolicy);
            disconnectBayeuxClient(client);
        }
    }

    @Test
    public void testPerf() throws Exception
    {
        boolean stress = Boolean.getBoolean("STRESS");
        Random random = new Random();

        final int rooms = stress ? 100 : 1;
        final int publish = stress ? 4000 : 100;
        final int batch = stress ? 10 : 2;
        final int pause = stress ? 50 : 10;
        BayeuxClient[] clients = new BayeuxClient[stress ? 500 : 2 * rooms];

        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger received = new AtomicInteger();

        for (int i = 0; i < clients.length; i++)
        {
            final AtomicBoolean connected = new AtomicBoolean();
            final BayeuxClient client = newBayeuxClient();
            final String room = "/channel/" + (i % rooms);
            clients[i] = client;

            client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    if (connected.getAndSet(false))
                        connections.decrementAndGet();

                    if (message.isSuccessful())
                    {
                        client.getChannel(room).subscribe(new ClientSessionChannel.MessageListener()
                        {
                            public void onMessage(ClientSessionChannel channel, Message message)
                            {
                                received.incrementAndGet();
                            }
                        });
                    }
                }
            });

            client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    if (!connected.getAndSet(message.isSuccessful()))
                    {
                        connections.incrementAndGet();
                    }
                }
            });

            clients[i].handshake();
            client.waitFor(1000, State.CONNECTED);
        }

        Assert.assertEquals(clients.length, connections.get());

        long start0 = System.currentTimeMillis();
        for (int i = 0; i < publish; i++)
        {
            final int sender = random.nextInt(clients.length);
            final String channel = "/channel/" + random.nextInt(rooms);

            String data = "data from " + sender + " to " + channel;
            // System.err.println(data);
            clients[sender].getChannel(channel).publish(data);

            if (i % batch == (batch - 1))
            {
                System.err.print('.');
                Thread.sleep(pause);
            }
            if (i % 1000 == 999)
                System.err.println();
        }
        System.err.println();

        int expected = clients.length * publish / rooms;

        long start = System.currentTimeMillis();
        while (received.get() < expected && (System.currentTimeMillis() - start) < 10000)
        {
            Thread.sleep(100);
            System.err.println("received " + received.get() + "/" + expected);
        }
        System.err.println((received.get() * 1000) / (System.currentTimeMillis() - start0) + " m/s");

        Assert.assertEquals(expected, received.get());

        for (BayeuxClient client : clients)
            Assert.assertTrue(client.disconnect(stress ? 5000 : 2000));
    }

    @Test
    public void testPublish() throws Exception
    {
        final BlockingArrayQueue<String> results = new BlockingArrayQueue<String>();

        String channelName = "/chat/msg";
        bayeux.createIfAbsent(channelName);
        bayeux.getChannel(channelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(10000, State.CONNECTED));

        String data = "Hello World";
        client.getChannel(channelName).publish(data);

        String id = results.poll(10, TimeUnit.SECONDS);
        Assert.assertEquals(client.getId(), id);
        Assert.assertEquals(channelName, results.poll(10, TimeUnit.SECONDS));
        Assert.assertEquals(data, results.poll(10, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testWaitFor() throws Exception
    {
        final BlockingArrayQueue<String> results = new BlockingArrayQueue<String>();

        String channelName = "/chat/msg";
        bayeux.createIfAbsent(channelName);
        bayeux.getChannel(channelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        long wait = 1000L;
        long start = System.nanoTime();
        client.handshake(wait);
        long stop = System.nanoTime();
        Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(stop - start) < wait);
        Assert.assertNotNull(client.getId());

        String data = "Hello World";
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                latch.countDown();
            }
        });
        client.getChannel(channelName).publish(data);

        Assert.assertEquals(client.getId(), results.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals(channelName, results.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals(data, results.poll(1, TimeUnit.SECONDS));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAuthentication() throws Exception
    {
        final AtomicReference<String> sessionId = new AtomicReference<String>();
        class A extends DefaultSecurityPolicy implements ServerSession.RemoveListener
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                Map<String, Object> ext = message.getExt();
                if (ext == null)
                    return false;

                Object authn = ext.get("authentication");
                if (!(authn instanceof Map))
                    return false;

                @SuppressWarnings("unchecked")
                Map<String, Object> authentication = (Map<String, Object>)authn;

                String token = (String)authentication.get("token");
                if (token == null)
                    return false;

                sessionId.set(session.getId());
                session.addListener(this);

                return true;
            }

            public void removed(ServerSession session, boolean timeout)
            {
                sessionId.set(null);
            }
        }
        A authenticator = new A();

        SecurityPolicy oldPolicy = bayeux.getSecurityPolicy();
        bayeux.setSecurityPolicy(authenticator);
        try
        {
            BayeuxClient client = newBayeuxClient();

            Map<String, Object> authentication = new HashMap<String, Object>();
            authentication.put("token", "1234567890");
            Message.Mutable fields = new HashMapMessage();
            fields.getExt(true).put("authentication", authentication);
            client.handshake(fields);

            Assert.assertTrue(client.waitFor(1000, State.CONNECTED));

            Assert.assertEquals(client.getId(), sessionId.get());

            disconnectBayeuxClient(client);

            Assert.assertNull(sessionId.get());
        }
        finally
        {
            bayeux.setSecurityPolicy(oldPolicy);
        }
    }

    @Test
    public void testClient() throws Exception
    {
        BayeuxClient client = newBayeuxClient();

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("<<" + message + " @ " + channel);
                if (message.isSuccessful())
                    handshakeLatch.countDown();
            }
        });
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("<<" + message + " @ " + channel);
                if (message.isSuccessful())
                    connectLatch.countDown();
            }
        });
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("<<" + message + " @ " + channel);
                if (message.isSuccessful())
                    subscribeLatch.countDown();
            }
        });
        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("<<" + message + " @ " + channel);
                if (message.isSuccessful())
                    unsubscribeLatch.countDown();
            }
        });

        client.handshake();
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch publishLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener subscriber = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println(" <" + message + " @ " + channel);
                publishLatch.countDown();
            }
        };
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        String data = "data";
        aChannel.publish(data);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        aChannel.unsubscribe(subscriber);
        Assert.assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
