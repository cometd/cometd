package org.cometd.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessagesAfterFailedHandshakeTest
{
    private Server server;
    private String cometdURL;
    private BayeuxServer bayeux;
    private HttpClient httpClient;

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
        bayeux.setSecurityPolicy(new Policy());

        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void destroy() throws Exception
    {
        httpClient.stop();

        server.stop();
        server.join();
    }

    @Test
    public void testSubscribeAfterFailedHandshake() throws Exception
    {
        final CountDownLatch serverLatch = new CountDownLatch(1);
        final String channelName = "/test_subscribe_after_failed_handshake";
        bayeux.getChannel(Channel.META_SUBSCRIBE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                serverLatch.countDown();
                return true;
            }
        });

        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient));
        client.setDebugEnabled(true);

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                        }
                    });
                    handshakeLatch.countDown();
                }
            }
        });
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    subscribeLatch.countDown();
            }
        });
        client.handshake();

        assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));
        assertTrue(subscribeLatch.await(1, TimeUnit.SECONDS));
        assertFalse(serverLatch.await(1, TimeUnit.SECONDS));

        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }

    @Test
    public void testPublishAfterFailedHandshake() throws Exception
    {
        final CountDownLatch serverLatch = new CountDownLatch(1);
        final String channelName = "/test_subscribe_after_failed_handshake";
        bayeux.createIfAbsent(channelName);
        bayeux.getChannel(channelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                serverLatch.countDown();
                return true;
            }
        });

        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient));
        client.setDebugEnabled(true);

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                {
                    client.getChannel(channelName).publish(new HashMap<String, Object>());
                    handshakeLatch.countDown();
                }
            }
        });
        final CountDownLatch publishLatch = new CountDownLatch(1);
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.countDown();
            }
        });
        client.handshake();

        assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));
        assertTrue(publishLatch.await(1, TimeUnit.SECONDS));
        assertFalse(serverLatch.await(1, TimeUnit.SECONDS));

        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }

    private class Policy extends DefaultSecurityPolicy
    {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            Map<String,Object> ext = message.getExt();
            return ext != null && ext.get("authn") != null;
        }
    }
}
