package org.cometd.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class MaxNetworkDelayTest
{
    private Server server;
    private BayeuxServer bayeux;
    private String cometdURL;
    private HttpClient httpClient;
    private long timeout = 5000;

    @Before
    public void setUp() throws Exception
    {
        server = new Server();

        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // Cometd servlet
        CometdServlet cometdServlet = new CometdServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        cometdServletHolder.setInitParameter("timeout", String.valueOf(timeout));
        cometdServletHolder.setInitParameter("logLevel", "3");

        String servletPath = "/cometd";
        context.addServlet(cometdServletHolder, servletPath + "/*");

        server.start();

        bayeux = cometdServlet.getBayeux();
        cometdURL = "http://localhost:" + connector.getLocalPort() + contextPath + servletPath;

        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void tearDown() throws Exception
    {
        httpClient.stop();

        server.stop();
        server.join();
    }

    @Test
    public void testMaxNetworkDelayOnHandshake() throws Exception
    {
        final long maxNetworkDelay = 2000;
        final long sleep = maxNetworkDelay + maxNetworkDelay / 2;

        bayeux.addExtension(new EmptyExtension()
        {
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
            {
                if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                {
                    try
                    {
                        Thread.sleep(sleep);
                        // If we are able to sleep the whole time, the test will fail
                    }
                    catch (InterruptedException x)
                    {
                        Thread.currentThread().interrupt();
                        // This exception is expected, do nothing
                    }
                }
                return true;
            }
        });

        final CountDownLatch latch = new CountDownLatch(2);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (x instanceof TimeoutException)
                    latch.countDown();
            }
        };
        client.setDebugEnabled(true);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    latch.countDown();
            }
        });

        client.handshake();
        assertTrue(latch.await(sleep, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testMaxNetworkDelayOnConnect() throws Exception
    {
        final long maxNetworkDelay = 2000;
        final long sleep = maxNetworkDelay + maxNetworkDelay / 2;

        bayeux.addExtension(new EmptyExtension()
        {
            private AtomicInteger connects = new AtomicInteger();

            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
            {
                if (Channel.META_CONNECT.equals(message.getChannel()))
                {
                    int c = connects.incrementAndGet();
                    if (c == 2)
                    {
                        try
                        {
                            Thread.sleep(sleep);
                            // If we are able to sleep the whole time, the test will fail
                        }
                        catch (InterruptedException x)
                        {
                            Thread.currentThread().interrupt();
                            // This exception is expected, do nothing
                        }
                    }
                }
                return true;
            }
        });

        final CountDownLatch latch = new CountDownLatch(3);
        LongPollingTransport transport = LongPollingTransport.create(null, httpClient);
        transport.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (x instanceof TimeoutException)
                    latch.countDown();
            }
        };
        client.setDebugEnabled(true);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            private AtomicInteger connects = new AtomicInteger();

            public void onMessage(ClientSessionChannel channel, Message message)
            {
                int c = connects.incrementAndGet();
                if (c == 1 && message.isSuccessful())
                    latch.countDown();
                else if (c == 2 && !message.isSuccessful())
                    latch.countDown();
            }
        });

        client.handshake();
        long begin = System.nanoTime();
        assertTrue(latch.await(timeout + sleep, TimeUnit.MILLISECONDS));
        long end = System.nanoTime();
        assertTrue(end - begin > TimeUnit.MILLISECONDS.toNanos(timeout));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    private class EmptyExtension implements BayeuxServer.Extension
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
            return true;
        }
    }
}
