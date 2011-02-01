package org.cometd.client;

import java.util.HashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HighRateServerEventsTest
{
    private final String channelName = "/foo";
    private Server server;
    private String cometdURL;
    private BayeuxServer bayeux;

    @Before
    public void init() throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup comet servlet
        CometdServlet cometdServlet = new CometdServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        cometdServletHolder.setInitParameter("logLevel", "3");
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();

        int port = connector.getLocalPort();
        String contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;

        bayeux = cometdServlet.getBayeux();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testHighRateServerEvents() throws Exception
    {
        LocalSession service = bayeux.newLocalSession("high_rate_test");
        service.handshake();

        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        client.setDebugEnabled(true);
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        final AtomicInteger messages = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(2);
        client.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel channel = client.getChannel(channelName);
                channel.subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messages.incrementAndGet();
                        try
                        {
                            barrier.await();
                        }
                        catch (Exception x)
                        {
                            failure.set(x);
                        }
                    }
                });
                channel.publish(new HashMap<String, Object>());
            }
        });

        // Wait until subscription is acknowledged
        barrier.await();
        assertNull(failure.get());

        long begin = System.nanoTime();
        int count = 500;
        for (int i = 0; i < count; ++i)
        {
            service.getChannel(channelName).publish(new HashMap<String, Object>());
            barrier.await();
            assertNull(failure.get());
        }
        long end = System.nanoTime();
        System.err.println("rate = " + count * 1000000000L / (end - begin) + " messages/s");

        assertEquals(count + 1, messages.get());

        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }
}
