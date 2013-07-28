/*
 * Copyright (c) 2011 the original author or authors.
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

package org.cometd.websocket.client;

import java.io.EOFException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.TransportException;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.cometd.bayeux.server.ConfigurableServerChannel.Initializer.Persistent;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest
{
    public BayeuxClientWebSocketTest(String implementation)
    {
        super(implementation);
    }

    @Before
    public void init() throws Exception
    {
        runServer(null);
    }

    @Test
    public void testClientCanNegotiateTransportWithServerNotSupportingWebSocket() throws Exception
    {
        bayeux.setAllowedTransports("long-polling");

        ClientTransport webSocketTransport = newWebSocketTransport(null);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof TransportException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    successLatch.countDown();
                else
                    failedLatch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientWithOnlyWebSocketCannotNegotiateWithServerNotSupportingWebSocket() throws Exception
    {
        bayeux.setAllowedTransports("long-polling");

        ClientTransport webSocketTransport = newWebSocketTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                    if (!(x.getCause() instanceof UpgradeException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    failedLatch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testClientRetriesWebSocketTransportIfCannotConnect() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        webSocketTransport.setOption(JettyWebSocketTransport.CONNECT_TIMEOUT_OPTION, 1000L);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            protected boolean sendConnect()
            {
                if ("websocket".equals(getTransport().getName()))
                    connectLatch.countDown();
                return super.sendConnect();
            }

            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof ConnectException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    failedLatch.countDown();
            }
        });

        int port = connector.getLocalPort();
        server.stop();

        client.handshake();

        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        connector.setPort(port);
        server.start();

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortThenRestart() throws Exception
    {
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (!(x instanceof EOFException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());
        client.handshake();

        // Need to be sure that the second connect is sent otherwise
        // the abort and rehandshake may happen before the second
        // connect and the test will fail.
        Thread.sleep(1000);

        client.abort();
        Assert.assertFalse(client.isConnected());

        // Restart
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testRestart() throws Exception
    {
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Suppress expected exceptions
                if ((x instanceof EOFException) || (x instanceof ConnectException))
                    return;
                super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful() && "websocket".equals(client.getTransport().getName()))
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
    public void testHandshakeExpiration() throws Exception
    {
        final long maxNetworkDelay = 2000;

        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                try
                {
                    Thread.sleep(maxNetworkDelay + maxNetworkDelay / 2);
                    return true;
                }
                catch (InterruptedException x)
                {
                    return false;
                }
            }
        });

        Map<String,Object> options = new HashMap<>();
        options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(options);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof TimeoutException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        // Expect 2 failed messages because the client backoffs and retries
        // This way we are sure that the late response from the first
        // expired handshake is not delivered to listeners
        final CountDownLatch latch = new CountDownLatch(2);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                if (!message.isSuccessful())
                    latch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(latch.await(maxNetworkDelay * 2 + client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectNotRespondedOnServerSidePublish() throws Exception
    {
        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel metaHandshake, Message handshake)
            {
                if (handshake.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            publishLatch.get().countDown();
                        }
                    });
                }
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        final ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlyTransport() throws Exception
    {
        stopServer();

        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, "true");
        options.put("ws." + org.cometd.websocket.server.JettyWebSocketTransport.THREAD_POOL_MAX_SIZE, "8");
        runServer(options);

        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel metaHandshake, Message handshake)
            {
                if (handshake.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            publishLatch.get().countDown();
                        }
                    });
                }
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        // from the server-side publish
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlySession() throws Exception
    {
        bayeux.addExtension(new BayeuxServer.Extension.Adapter()
        {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
            {
                if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                {
                    if (to != null && !to.isLocalSession())
                        ((ServerSessionImpl)to).setMetaConnectDeliveryOnly(true);
                }
                return true;
            }
        });

        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel metaHandshake, Message handshake)
            {
                if (handshake.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            publishLatch.get().countDown();
                        }
                    });
                }
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        final ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectExpires() throws Exception
    {
        stopServer();
        long timeout = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        runServer(options);

        final BayeuxClient client = newBayeuxClient();
        final CountDownLatch connectLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.countDown();
                if (connectLatch.getCount() == 0)
                    client.disconnect();
            }
        });
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_DISCONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                disconnectLatch.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(connectLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));
        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testWebSocketWithAckExtension() throws Exception
    {
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (!(x instanceof EOFException || x instanceof ConnectException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        bayeux.addExtension(new AcknowledgedMessagesExtension());
        client.addExtension(new AckExtension());

        final String channelName = "/chat/demo";
        final BlockingQueue<Message> messages = new BlockingArrayQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            messages.add(message);
                        }
                    });
                }
            }
        });
        final CountDownLatch subscribed = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful() && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD)))
                    subscribed.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, messages.size());

        final ServerChannel chatChannel = bayeux.getChannel(channelName);
        Assert.assertNotNull(chatChannel);

        final int count = 5;
        client.batch(new Runnable()
        {
            public void run()
            {
                for (int i = 0; i < count; ++i)
                    client.getChannel(channelName).publish("hello_" + i);
            }
        });

        for (int i = 0; i < count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        int port = connector.getLocalPort();
        connector.stop();
        Thread.sleep(1000);
        Assert.assertTrue(connector.isStopped());
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send messages while client is offline
        for (int i = count; i < 2 * count; ++i)
            chatChannel.publish(null, "hello_" + i);

        Thread.sleep(1000);
        Assert.assertEquals(0, messages.size());

        connector.setPort(port);
        connector.start();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Check that the offline messages are received
        for (int i = count; i < 2 * count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        // Send messages while client is online
        client.batch(new Runnable()
        {
            public void run()
            {
                for (int i = 2 * count; i < 3 * count; ++i)
                    client.getChannel(channelName).publish("hello_" + i);
            }
        });

        // Check if messages after reconnect are received
        for (int i = 2 * count; i < 3 * count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDelayedOnServerRespondedBeforeRetry() throws Exception
    {
        final long maxNetworkDelay = 2000;
        final long backoffIncrement = 2000;
        testMetaConnectDelayedOnServer(maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement / 2);
    }

    @Test
    public void testMetaConnectDelayedOnServerRespondedAfterRetry() throws Exception
    {
        final long maxNetworkDelay = 2000;
        final long backoffIncrement = 1000;
        testMetaConnectDelayedOnServer(maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement * 2);
    }

    private void testMetaConnectDelayedOnServer(final long maxNetworkDelay, final long backoffIncrement, final long delay) throws Exception
    {
        Map<String, Object> options = new HashMap<>();
        options.put("ws.maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(options);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (!(x instanceof TimeoutException))
                    super.onFailure(x, messages);
            }
        };
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        client.setDebugEnabled(debugTests());

        bayeux.stop();
        long timeout = 5000;
        bayeux.setOption("timeout", timeout);
        bayeux.addTransport(new org.cometd.websocket.server.JettyWebSocketTransport(bayeux));
        bayeux.setAllowedTransports("websocket");
        bayeux.start();

        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener()
        {
            private final AtomicInteger connects = new AtomicInteger();
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                int connects = this.connects.incrementAndGet();
                if (connects == 2)
                {
                    try
                    {
                        // We delay the second connect, so the client can expire it
                        Thread.sleep(delay);
                    }
                    catch (InterruptedException x)
                    {
                        x.printStackTrace();
                        return false;
                    }
                }
                return true;
            }
        });

        // The second connect must fail, and should never be notified on client
        final CountDownLatch connectLatch1 = new CountDownLatch(2);
        final CountDownLatch connectLatch2 = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            private final AtomicInteger connects = new AtomicInteger();
            public String failedId;
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                int connects = this.connects.incrementAndGet();
                if (connects == 1 && message.isSuccessful())
                {
                    connectLatch1.countDown();
                }
                else if (connects == 2 && !message.isSuccessful())
                {
                    connectLatch1.countDown();
                    failedId = message.getId();
                }
                else if (connects > 2 && !failedId.equals(message.getId()))
                {
                    connectLatch2.countDown();
                }
            }
        });

        client.handshake();

        Assert.assertTrue(connectLatch1.await(timeout + 2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
        Assert.assertTrue(connectLatch2.await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientSendsAndReceivesBigMessage() throws Exception
    {
        stopServer();

        int maxMessageSize = 128 * 1024;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("ws.maxMessageSize", String.valueOf(maxMessageSize));
        runServer(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("ws.maxMessageSize", maxMessageSize);
        ClientTransport webSocketTransport = newWebSocketTransport(clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel("/test");
        final CountDownLatch latch = new CountDownLatch(1);
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                latch.countDown();
            }
        });

        char[] data = new char[maxMessageSize * 3 / 4];
        Arrays.fill(data, 'x');
        channel.publish(new String(data));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientSendsAndReceivesBigMessageWithBigBuffer() throws Exception
    {
        stopServer();

        int maxMessageSize = 512 * 1024;
        int bufferSize = maxMessageSize / 4;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("ws." + org.cometd.websocket.server.JettyWebSocketTransport.BUFFER_SIZE_OPTION, String.valueOf(bufferSize));
        serverOptions.put("ws." + org.cometd.websocket.server.JettyWebSocketTransport.MAX_MESSAGE_SIZE_OPTION, String.valueOf(maxMessageSize));
        runServer(serverOptions);

        // TODO: why this stop()+start() ?
        wsClient.stop();
        //wsClient.setBufferSize(bufferSize);
        wsClient.start();

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("ws." + JettyWebSocketTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        ClientTransport webSocketTransport = newWebSocketTransport(clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel("/test");
        final CountDownLatch latch = new CountDownLatch(1);
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                latch.countDown();
            }
        });

        char[] data = new char[maxMessageSize * 3 / 4];
        Arrays.fill(data, 'x');
        channel.publish(new String(data));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientDisconnectingClosesTheConnection() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        bayeux.setTransports(newWebSocketServerTransport(closeLatch));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect();

        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientDisconnectingSynchronouslyClosesTheConnection() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        bayeux.setTransports(newWebSocketServerTransport(closeLatch));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect(1000);

        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    private ServerTransport newWebSocketServerTransport(final CountDownLatch latch)
    {
        switch (implementation)
        {
            case WEBSOCKET_JSR_356:
                return new WebSocketTransport(bayeux)
                {
                    {
                        init();
                    }

                    @Override
                    protected void onClose(int code, String reason)
                    {
                        latch.countDown();
                    }
                };
            case WEBSOCKET_JETTY:
                return new org.cometd.websocket.server.JettyWebSocketTransport(bayeux)
                {
                    {
                        init();
                    }

                    @Override
                    protected void onClose(int code, String reason)
                    {
                        latch.countDown();
                    }
                };
            default:
                throw new IllegalArgumentException();
        }
    }

    @Test
    public void testWhenClientAbortsServerSessionIsSwept() throws Exception
    {
        stopServer();

        Map<String, String> options = new HashMap<>();
        long maxInterval = 1000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        runServer(options);

        ClientTransport webSocketTransport = newWebSocketTransport(null);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (!(x instanceof EOFException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow long poll to establish
        Thread.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(client.getId());
        session.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                latch.countDown();
            }
        });

        client.abort();

        Assert.assertTrue(latch.await(2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDisconnectWithPendingMetaConnectWithoutResponseDoesNotExpire() throws Exception
    {
        stopServer();

        final long timeout = 2000L;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("timeout", String.valueOf(timeout));
        runServer(serverOptions);

        bayeux.addExtension(new BayeuxServer.Extension.Adapter()
        {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
            {
                if (Channel.META_CONNECT.equals(message.getChannel()))
                {
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null)
                    {
                        if (Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)))
                        {
                            // Pretend this message could not be sent
                            return false;
                        }
                    }
                }
                return true;
            }
        });

        final long maxNetworkDelay = 2000L;
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Suppress expected exceptions
                if (!(x instanceof EOFException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held on server
        TimeUnit.MILLISECONDS.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    latch.countDown();
            }
        });

        client.disconnect();

        Assert.assertFalse(latch.await(2 * maxNetworkDelay + timeout, TimeUnit.MILLISECONDS));
    }
}
