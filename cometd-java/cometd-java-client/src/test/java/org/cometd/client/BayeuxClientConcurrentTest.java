package org.cometd.client;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BayeuxClientConcurrentTest
{
    private static Server server;
    private static HttpClient httpClient;
    private static String cometdURL;
    private static BayeuxServer bayeux;

    @BeforeClass
    public static void startServer() throws Exception
    {
        // Manually construct context to avoid hassles with webapp classloaders for now.
        server = new Server();

        Connector connector = new SelectChannelConnector();
        connector.setMaxIdleTime(30000);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // Cometd servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("multiFrameInterval", "2000");
        cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + contextPath + cometdServletPath;

        bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        httpClient.stop();

        server.stop();
        server.join();
    }

    @Test
    public void testHandshakeFailsConcurrentDisconnect() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected boolean sendHandshake()
            {
                disconnect();
                boolean result = super.sendHandshake();
                assertFalse(result);
                latch.countDown();
                return result;
            }
        };
        client.setDebugEnabled(false);
        client.handshake();
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));

        assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
    }

    @Test
    public void testConnectFailsConcurrentDisconnect() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected boolean sendConnect()
            {
                disconnect();
                boolean result = super.sendConnect();
                assertFalse(result);
                latch.countDown();
                return result;
            }
        };
        client.setDebugEnabled(false);
        client.handshake();
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));

        assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
    }

    @Test
    public void testSubscribeFailsConcurrentDisconnect() throws Exception
    {
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected void enqueueSend(Message.Mutable message)
            {
                disconnect();
                super.enqueueSend(message);
            }
        };
        client.setDebugEnabled(false);
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
        assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        client.getChannel("/test").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentDisconnect() throws Exception
    {
        final String channelName = "/test";
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected void enqueueSend(Message.Mutable message)
            {
                if (channelName.equals(message.getChannel()))
                    disconnect();
                super.enqueueSend(message);
            }
        };
        client.setDebugEnabled(false);
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
        assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

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
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));

        // Publish will fail
        channel.publish(new HashMap());
        assertTrue(failLatch.await(1000, TimeUnit.MILLISECONDS));
        assertFalse(publishLatch.await(1000, TimeUnit.MILLISECONDS));

        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testPublishFailsConcurrentNetworkDown() throws Exception
    {
        final String channelName = "/test";
        final AtomicInteger connects = new AtomicInteger();
        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
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
        client.setDebugEnabled(false);
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

        assertTrue(client.waitFor(1000, BayeuxClient.State.UNCONNECTED));

        channel.publish(new HashMap());
        assertTrue(publishLatch.await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testHandshakeListenersAreNotifiedBeforeConnectListeners() throws Exception
    {
        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient));
        client.setDebugEnabled(false);
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

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testConcurrentHandshakeAndBatch() throws Exception
    {
        final CountDownLatch sendLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected boolean sendMessages(Message.Mutable... messages)
            {
                sendLatch.countDown();
                return super.sendMessages(messages);
            }
        };
        client.setDebugEnabled(false);

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
                    assertFalse(sendLatch.await(1000, TimeUnit.MILLISECONDS));
                }
                catch (InterruptedException x)
                {
                    // Ignored
                }

                channel.publish("DATA");
            }
        });

        assertTrue(sendLatch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(messageLatch.await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }
}
