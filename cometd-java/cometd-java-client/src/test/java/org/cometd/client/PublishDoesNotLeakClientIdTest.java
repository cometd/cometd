package org.cometd.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PublishDoesNotLeakClientIdTest
{
    private Server server;
    private String cometdURL;

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
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testPublishDoesNotLeakClientId() throws Exception
    {
        BayeuxClient client1 = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        client1.handshake();
        try
        {
            assertTrue(client1.waitFor(1000, BayeuxClient.State.CONNECTED));

            BayeuxClient client2 = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
            client2.handshake();
            try
            {
                assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

                assertFalse(client1.getId().equals(client2.getId()));

                String channel = "/test";
                final CountDownLatch subscribe = new CountDownLatch(1);
                client1.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        subscribe.countDown();
                    }
                });
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<Message> messageRef = new AtomicReference<Message>();
                client1.getChannel(channel).subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messageRef.set(message);
                        latch.countDown();
                    }
                });
                assertTrue(subscribe.await(1000, TimeUnit.MILLISECONDS));

                client2.getChannel(channel).publish(client2.newMessage());

                assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
                assertNull(messageRef.get().getClientId());
            }
            finally
            {
                client2.disconnect();
                client2.waitFor(1000, BayeuxClient.State.DISCONNECTED);
            }
        }
        finally
        {
            client1.disconnect();
            client1.waitFor(1000, BayeuxClient.State.DISCONNECTED);
        }
    }
}
