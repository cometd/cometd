package org.cometd.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ServerRestartTest
{
    private Server server;
    private Connector connector;
    private String cometdURL;
    private HttpClient httpClient;

    @Before
    public void init() throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup comet servlet
        CometdServlet cometdServlet = new CometdServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitParameter("maxInterval", "30000");
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();

        int port = connector.getLocalPort();
        String contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;

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
    public void testServerRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> sendLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(3));
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            public void onSending(Message[] messages)
            {
                super.onSending(messages);
                sendLatch.get().countDown();
            }
        };
        long backoffIncrement = 500;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        client.handshake();

        // Be sure the second connect has been sent to the server
        assertTrue(sendLatch.get().await(1, TimeUnit.SECONDS));

        // Wait a little more
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        server.stop();
        server.join();

        // Wait a few retries
        Thread.sleep(backoffIncrement + 2 * backoffIncrement + 3 * backoffIncrement);

        // Add listeners to check the behavior of the client
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                handshakeLatch.countDown();
            }
        });
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.countDown();
            }
        });
        // Expect handshake and 2 connects messages to be sent
        sendLatch.set(new CountDownLatch(3));

        connector.setPort(port);
        server.start();

        assertTrue(handshakeLatch.await(5 * backoffIncrement, TimeUnit.MILLISECONDS));
        assertTrue(connectLatch.await(1, TimeUnit.SECONDS));
        assertTrue(sendLatch.get().await(1, TimeUnit.SECONDS));

        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }
}
