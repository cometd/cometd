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

package org.cometd.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.common.TransportException;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientTest extends ClientServerTest
{
    private boolean stress = Boolean.getBoolean("STRESS");
    private Random random = new Random();

    @Before
    public void setUp() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testIPv6Address() throws Exception
    {
        cometdURL = cometdURL.replace("localhost", "[::1]");

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow long poll to establish
        Thread.sleep(1000);

        disconnectBayeuxClient(client);
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

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(channelName, messages.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals("hello", messages.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeFailsBeforeSend() throws Exception
    {
        LongPollingTransport transport = new LongPollingTransport(null, httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);
                // Remove the address so that the send will fail
                exchange.setAddress(null);
            }
        };
        transport.setDebugEnabled(debugTests());
        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        Assert.assertTrue(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        Assert.assertTrue(client.disconnect(1000));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assert.assertFalse(latch.get().await(client.getBackoffIncrement() * 3, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testHandshakeFailsBadTransport() throws Exception
    {
        LongPollingTransport transport = new LongPollingTransport(null, httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);
                // Modify the exchange so that the server chokes it
                exchange.setMethod("PUT");
            }
        };
        transport.setDebugEnabled(debugTests());
        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        Assert.assertTrue(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        Assert.assertTrue(client.disconnect(1000));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assert.assertFalse(latch.get().await(client.getBackoffIncrement() * 3, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testHandshakeDenied() throws Exception
    {
        BayeuxClient client = newBayeuxClient();
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                return false;
            }
        });
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
        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assert.assertFalse(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        Assert.assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeFailsNoTransports() throws Exception
    {
        final AtomicReference<Message> handshake = new AtomicReference<Message>();
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);

        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            protected void processHandshake(Message.Mutable message)
            {
                // Force no transports
                message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new Object[0]);
                super.processHandshake(message);
            }

            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                handshake.set(message);
                handshakeLatch.countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));

        Assert.assertFalse(handshake.get().isSuccessful());
        Assert.assertTrue(handshake.get().containsKey(Message.ERROR_FIELD));
        Assert.assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());

        // Be sure the connect is not tried
        Assert.assertFalse(connectLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeRetries() throws Exception
    {
        context.stop();
        TestFilter filter = new TestFilter();
        context.addFilter(new FilterHolder(filter), "/*", FilterMapping.DEFAULT);
        context.start();

        final BlockingArrayQueue<Message> queue = new BlockingArrayQueue<Message>(100, 100);
        final AtomicBoolean connected = new AtomicBoolean(false);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                Message.Mutable problem = newMessage();
                problem.setSuccessful(false);
                problem.put("exception", x);
                queue.offer(problem);
            }
        };
        client.setDebugEnabled(debugTests());

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
                if (message.isSuccessful())
                    queue.offer(message);
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
                if (message.isSuccessful())
                    queue.offer(message);
            }
        });

        client.getChannel("/**").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel session, Message message)
            {
                if (message.getData() != null || Channel.META_SUBSCRIBE.equals(message.getChannel()) || Channel.META_DISCONNECT.equals(message.getChannel()))
                {
                    queue.offer(message);
                }
            }
        });

        long backoffIncrement = 1000L;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        filter.code = 503;
        client.handshake();

        Message message = queue.poll(backoffIncrement, TimeUnit.MILLISECONDS);
        Assert.assertFalse(message.isSuccessful());
        Object exception = message.get("exception");
        Assert.assertTrue(exception instanceof TransportException);

        message = queue.poll(2000 + 2 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assert.assertFalse(message.isSuccessful());
        exception = message.get("exception");
        Assert.assertTrue(exception instanceof TransportException);

        message = queue.poll(2000 + 3 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assert.assertFalse(message.isSuccessful());
        exception = message.get("exception");
        Assert.assertTrue(exception instanceof TransportException);

        filter.code = 0;

        message = queue.poll(2000 + 4 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assert.assertTrue(message.isSuccessful());
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testConnectRetries() throws Exception
    {
        final AtomicInteger connects = new AtomicInteger();
        final CountDownLatch attempts = new CountDownLatch(4);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            protected boolean scheduleConnect(long interval, long backoff)
            {
                int count = connects.get();
                if (count > 0)
                {
                    Assert.assertEquals((count - 1) * getBackoffIncrement(), backoff);
                    attempts.countDown();
                }
                return super.scheduleConnect(interval, backoff);
            }

            @Override
            protected boolean sendConnect()
            {
                if (connects.incrementAndGet() < 2)
                    return super.sendConnect();

                Message.Mutable connect = newMessage();
                connect.setChannel(Channel.META_CONNECT);
                connect.setSuccessful(false);
                processConnect(connect);
                return false;
            }
        };
        client.setDebugEnabled(debugTests());
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // It should backoff and retry
        // Wait for 2 attempts, which will happen at +1s and +3s (doubled for safety)
        Assert.assertTrue(attempts.await(client.getBackoffIncrement() * 8, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPerf() throws Exception
    {
        Runtime.getRuntime().addShutdownHook(new DumpThread());

        final int rooms = stress ? 100 : 10;
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
            client.waitFor(5000, State.CONNECTED);
        }

        Assert.assertEquals(clients.length, connections.get());

        long start0 = System.nanoTime();
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

        long start = System.nanoTime();
        while (received.get() < expected && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10)
        {
            Thread.sleep(100);
            System.err.println("received " + received.get() + "/" + expected);
        }
        System.err.println((received.get() * 1000 * 1000 * 1000L) / (System.nanoTime() - start0) + " m/s");

        Assert.assertEquals(expected, received.get());

        for (BayeuxClient client : clients)
            Assert.assertTrue(client.disconnect(1000));
    }

    @Test
    public void testPublish() throws Exception
    {
        final BlockingArrayQueue<String> results = new BlockingArrayQueue<String>();

        String channelName = "/chat/msg";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener()
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
        Assert.assertTrue(client.waitFor(5000, State.CONNECTED));

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
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener()
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
        client.getChannel(channelName).publish(data);

        Assert.assertEquals(client.getId(), results.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals(channelName, results.poll(1, TimeUnit.SECONDS));
        Assert.assertEquals(data, results.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testWaitForImpliedState() throws Exception
    {
        final BayeuxClient client = newBayeuxClient();
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful() && client.isHandshook())
                    latch.countDown();
            }
        });

        client.handshake();
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, State.HANDSHAKING));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testURLWithImplicitPort() throws Exception
    {
        final AtomicBoolean listening = new AtomicBoolean();
        try
        {
            Socket socket = new Socket("localhost", 80);
            socket.close();
            listening.set(true);
        }
        catch (ConnectException x)
        {
            listening.set(false);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient("http://localhost/cometd", transport);
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());

                // If port 80 is listening, it's probably some other HTTP server
                // and a bayeux request will result in a 404, which is converted
                // to a TransportException; if not listening, it will be a ConnectException
                Map<String, Object> failure = (Map<String, Object>)message.get("failure");
                Assert.assertNotNull(failure);
                Object exception = failure.get("exception");
                if (listening.get())
                    Assert.assertTrue(exception instanceof TransportException);
                else
                    Assert.assertTrue(exception instanceof ConnectException);
                latch.countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortNotifiesListeners() throws Exception
    {
        BayeuxClient client = newBayeuxClient();

        final CountDownLatch connectLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (connectLatch.getCount() > 1 && message.isSuccessful() ||
                        connectLatch.getCount() == 1 && !message.isSuccessful())
                connectLatch.countDown();
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, State.CONNECTED));

        // Wait for connect
        Thread.sleep(1000);

        client.abort();

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortThenRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onSending(Message[] messages)
            {
                // Need to be sure that the second connect is sent otherwise
                // the abort and rehandshake may happen before the second
                // connect and the test will fail.
                super.onSending(messages);
                if (messages.length == 1 && Channel.META_CONNECT.equals(messages[0].getChannel()))
                    connectLatch.get().countDown();
            }
        };
        client.setDebugEnabled(debugTests());
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        client.abort();
        Assert.assertFalse(client.isConnected());

        // Restart
        connectLatch.set(new CountDownLatch(2));
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortBeforePublishThenRestart() throws Exception
    {
        final String channelName = "/service/test";
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final CountDownLatch publishLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            protected AbstractSessionChannel newChannel(ChannelId channelId)
            {
                return new BayeuxClientChannel(channelId)
                {
                    @Override
                    public void publish(Object data)
                    {
                        abort();
                        super.publish(data);
                    }
                };
            }

            @Override
            protected boolean sendMessages(Message.Mutable... messages)
            {
                boolean result = super.sendMessages(messages);
                if (result)
                    publishLatch.countDown();
                return result;
            }
        };
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    failureLatch.countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        client.getChannel(channelName).publish(new HashMap<String, Object>());
        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        // Publish must not be sent
        Assert.assertFalse(publishLatch.await(1, TimeUnit.SECONDS));
        Assert.assertFalse(client.isConnected());

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));
        // Check that publish has not been queued and is not sent on restart
        Assert.assertFalse(publishLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortAfterPublishThenRestart() throws Exception
    {
        final String channelName = "/test";
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            protected boolean sendMessages(Message.Mutable... messages)
            {
                abort();
                publishLatch.get().countDown();
                return super.sendMessages(messages);
            }
        };
        client.setDebugEnabled(debugTests());
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        ClientSessionChannel channel = client.getChannel(channelName);
        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.get().countDown();
            }
        });

        abort.set(true);
        channel.publish(new HashMap<String, Object>());
        Assert.assertTrue(publishLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertFalse(client.isConnected());

        // Message must not be received
        Assert.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(10, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testRestart() throws Exception
    {
        BayeuxClient client = newBayeuxClient();

        final AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectedLatch.get().countDown();
                else
                    disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        server.stop();
        Assert.assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(!client.isConnected());

        // restart server
        connector.setPort(port);
        connectedLatch.set(new CountDownLatch(1));
        server.start();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.isConnected());

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

        bayeux.setSecurityPolicy(authenticator);
        BayeuxClient client = newBayeuxClient();

        Map<String, Object> authentication = new HashMap<String, Object>();
        authentication.put("token", "1234567890");
        Message.Mutable fields = new HashMapMessage();
        fields.getExt(true).put("authentication", authentication);
        client.handshake(fields);

        Assert.assertTrue(client.waitFor(5000, State.CONNECTED));

        Assert.assertEquals(client.getId(), sessionId.get());

        disconnectBayeuxClient(client);

        Assert.assertNull(sessionId.get());
    }

    @Test
    public void testSubscribeToSlashStarStarDoesNotSendMetaMessages() throws Exception
    {
        stopServer();

        long timeout = 5000;
        Map<String, String> serverParams = new HashMap<String, String>();
        serverParams.put("timeout", String.valueOf(timeout));
        startServer(serverParams);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, State.CONNECTED));
        // Allow long poll to establish
        Thread.sleep(1000);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    subscribeLatch.countDown();
            }
        });

        String channelName = "/**";
        final CountDownLatch publishLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                publishLatch.countDown();
            }
        });

        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Register a listener to a service channel, to be sure that
        // they are not broadcasted due to the subscription to /**
        final CountDownLatch serviceLatch = new CountDownLatch(1);
        client.getChannel("/service/foo").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                // Ignore publish reply, only care about message with data
                if (message.containsKey(Message.DATA_FIELD))
                    serviceLatch.countDown();
            }
        });

        // Register a listener to /meta/connect, to be sure that /meta/connect messages
        // sent by the client are not broadcasted back due to the subscription to /**
        final CountDownLatch metaLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    metaLatch.countDown();
            }
        });

        client.getChannel("/foo").publish(new HashMap<String, Object>());
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        client.getChannel("/service/foo").publish(new HashMap<String, Object>());
        Assert.assertFalse(serviceLatch.await(1, TimeUnit.SECONDS));

        Assert.assertFalse(metaLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testStateUnSubscribes() throws Exception
    {
        final BlockingArrayQueue<Object> results = new BlockingArrayQueue<Object>();

        final AtomicBoolean failHandShake = new AtomicBoolean(true);

        LongPollingTransport transport = new LongPollingTransport(null, httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);

                if (failHandShake.compareAndSet(true, false))
                    exchange.setAddress(null);
            }
        };
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.setDebugEnabled(debugTests());

        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                results.offer(message);
            }
        });

        client.handshake();

        // Subscribe without waiting
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // First handshake fails
        Message message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertFalse(message.isSuccessful());

        // Second handshake works
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assert.assertNotNull(id);

        boolean subscribe = false;
        boolean connect = false;
        for (int i = 0; i < 2; ++i)
        {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assert.assertNotNull(message);
            subscribe |= Channel.META_SUBSCRIBE.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        Assert.assertTrue(subscribe);
        Assert.assertTrue(connect);

        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // No second subscribe sent, be sure to wait less than the timeout
        // otherwise we get a connect message
        message = (Message)results.poll(5, TimeUnit.SECONDS);
        Assert.assertNull(message);

        client.disconnect();
        boolean disconnect = false;
        connect = false;
        for (int i = 0; i < 2; ++i)
        {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assert.assertNotNull(message);
            disconnect |= Channel.META_DISCONNECT.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        Assert.assertTrue(disconnect);
        Assert.assertTrue(connect);
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Rehandshake
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        results.clear();
        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel()))
            message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        // Restart server
        int port = connector.getLocalPort();
        server.stop();
        server.join();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        connector.setPort(port);
        server.start();

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        results.clear();

        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel()))
            message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeOverHTTPReportsHTTPFailure() throws Exception
    {
        startServer(null);
        // No transports on server, to make the client fail
        ((BayeuxServerImpl)bayeux).setAllowedTransports();

        BayeuxClient client = newBayeuxClient();
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                // Verify the failure object is there
                Map<String, Object> failure = (Map<String, Object>)message.get("failure");
                Assert.assertNotNull(failure);
                // Verify that the transport is there
                Assert.assertEquals("long-polling", failure.get(Message.CONNECTION_TYPE_FIELD));
                // Verify the original message is there
                Assert.assertNotNull(failure.get("message"));
                // Verify the HTTP status code is there
                Assert.assertEquals(400, failure.get("httpCode"));
                // Verify the exception string is there
                Assert.assertNotNull(failure.get("exception"));
                latch.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCustomTransportURL() throws Exception
    {
        startServer(null);

        LongPollingTransport transport = new LongPollingTransport(cometdURL, null, httpClient);
        transport.setDebugEnabled(debugTests());
        // Pass a bogus URL that must not be used
        BayeuxClient client = new BayeuxClient("http://foo/bar", transport);
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    private class DumpThread extends Thread
    {
        public void run()
        {
            try
            {
                if (server != null)
                    server.dump();
                if (httpClient != null)
                    httpClient.dump();
            }
            catch (Exception x)
            {
                x.printStackTrace();
            }
        }
    }

    private static class TestFilter implements Filter
    {
        volatile int code = 0;

        public void destroy()
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (code != 0)
                ((HttpServletResponse)response).sendError(code);
            else
                chain.doFilter(request, response);
        }

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }
    }
}
