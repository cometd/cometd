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

import java.net.ConnectException;
import java.net.ProtocolException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testClientCanNegotiateTransportWithServerNotSupportingWebSocket() throws Exception
    {
        bayeux.setAllowedTransports("long-polling");

        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        LongPollingTransport longPollingTransport = LongPollingTransport.create(null);
        longPollingTransport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof ProtocolException))
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
    public void testClientRetriesWebSocketTransportIfCannotConnect() throws Exception
    {
        int port = connector.getLocalPort();
        stopServer();

        final CountDownLatch connectLatch = new CountDownLatch(2);
        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        LongPollingTransport longPollingTransport = LongPollingTransport.create(null);
        longPollingTransport.setDebugEnabled(debugTests());
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
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
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
        Assert.assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.abort();
        Assert.assertFalse(client.isConnected());

        // Restart
        connectLatch.set(new CountDownLatch(2));
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));
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

        Map<String,Object> options = new HashMap<String, Object>();
        options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        WebSocketTransport transport = WebSocketTransport.create(options);
        transport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, transport)
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
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
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
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
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
        bayeux.getChannel(channelName).publish(emitter, data, null);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        bayeux.createIfAbsent(serviceChannelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        bayeux.getChannel(serviceChannelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data, null);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlyTransport() throws Exception
    {
        stopServer();

        Map<String, String> options = new HashMap<String, String>();
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, "true");
        options.put("ws." + org.cometd.websocket.server.WebSocketTransport.THREAD_POOL_MAX_SIZE, "8");
        startServer(options);

        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
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
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
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
        bayeux.getChannel(channelName).publish(emitter, data, null);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        bayeux.createIfAbsent(serviceChannelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        bayeux.getChannel(serviceChannelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data, null);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlySession() throws Exception
    {
        bayeux.addExtension(new BayeuxServer.Extension()
        {
            public boolean rcv(ServerSession from, ServerMessage.Mutable message)
            {
                return true;
            }

            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
            {
                return true;
            }

            public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
            {
                return true;
            }

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
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
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
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
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
        bayeux.getChannel(channelName).publish(emitter, data, null);

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        bayeux.createIfAbsent(serviceChannelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        bayeux.getChannel(serviceChannelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                bayeux.getChannel(channelName).publish(emitter, data, null);
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectExpires() throws Exception
    {
        stopServer();
        long timeout = 2000;
        Map<String, String> options = new HashMap<String, String>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        startServer(options);

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
        client.handshake();

        Assert.assertTrue(connectLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));
    }
}
