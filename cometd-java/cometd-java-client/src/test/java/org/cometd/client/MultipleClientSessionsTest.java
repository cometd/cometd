package org.cometd.client;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Simulates a browser opening multiple tabs to the same Bayeux server
 */
public class MultipleClientSessionsTest
{
    private Server server;
    private String cometdURL;
    private long timeout = 5000L;
    private long multiSessionInterval = timeout / 5;

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
        cometdServletHolder.setInitParameter("timeout", String.valueOf(5000));
        cometdServletHolder.setInitParameter("logLevel", "2");
        cometdServletHolder.setInitParameter("long-polling.timeout", String.valueOf(timeout));
        cometdServletHolder.setInitParameter("long-polling.multiSessionInterval", String.valueOf(multiSessionInterval));
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
    public void testCookieSentOnHandshakeResponse() throws Exception
    {
        final AtomicReference<String> browserCookie = new AtomicReference<String>();
        final BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                browserCookie.set(client.getCookie("BAYEUX_BROWSER"));
            }
        });
        client.handshake();
        assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));
        assertNotNull(browserCookie.get());

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testMultipleClientSessions() throws Exception
    {
        BayeuxClient client1 = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        final ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<Message>();
        client1.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connects1.offer(message);
            }
        });
        client1.handshake();
        assertTrue(client1.waitFor(1000, BayeuxClient.State.CONNECTED));
        String cookie = client1.getCookie("BAYEUX_BROWSER");
        assertNotNull(cookie);

        // Give some time to the first client to establish the long poll before the second client
        Thread.sleep(500);

        BayeuxClient client2 = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        final ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<Message>();
        client2.setCookie("BAYEUX_BROWSER", cookie);
        client2.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connects2.offer(message);
            }
        });
        client2.handshake();
        assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

        Thread.sleep(500);

        BayeuxClient client3 = new BayeuxClient(cometdURL, LongPollingTransport.create(null));
        final ConcurrentLinkedQueue<Message> connects3 = new ConcurrentLinkedQueue<Message>();
        client3.setCookie("BAYEUX_BROWSER", cookie);
        client3.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connects3.offer(message);
            }
        });
        client3.handshake();
        assertTrue(client3.waitFor(1000, BayeuxClient.State.CONNECTED));

        // Sleep for a while
        assertEquals(timeout, 5 * multiSessionInterval);
        Thread.sleep(2 * multiSessionInterval);

        // The first client must remain in long poll mode
        assertEquals(1, connects1.size());

        // Second client must be in normal poll mode
        assertTrue(connects2.size() > 1);
        Message lastConnect2 = new LinkedList<Message>(connects2).getLast();
        Map<String,Object> advice2 = lastConnect2.getAdvice();
        assertNotNull(advice2);
        assertTrue(advice2.containsKey("multiple-clients"));
        assertEquals(true, advice2.get("multiple-clients"));

        // Third client must be in normal poll mode
        assertTrue(connects3.size() > 1);
        Message lastConnect3 = new LinkedList<Message>(connects3).getLast();
        Map<String,Object> advice3 = lastConnect3.getAdvice();
        assertNotNull(advice3);
        assertTrue(advice3.containsKey("multiple-clients"));
        assertEquals(true, advice3.get("multiple-clients"));

        // Wait for the first client to re-issue a long poll
        Thread.sleep(timeout);

        // First client must still be in long poll mode
        assertEquals(2, connects1.size());

        // Abort abruptly the first client
        // Another client must switch to long poll
        client1.abort();

        // Sleep another timeout to be sure client1 does not poll
        Thread.sleep(timeout);
        assertEquals(2, connects1.size());

        // Loop until one of the other clients switched to long poll
        BayeuxClient client4 = null;
        BayeuxClient client5 = null;
        for (int i = 0; i < 10; ++i)
        {
            lastConnect2 = new LinkedList<Message>(connects2).getLast();
            advice2 = lastConnect2.getAdvice();
            if (advice2 == null || !advice2.containsKey("multiple-clients"))
            {
                client4 = client2;
                client5 = client3;
                break;
            }
            lastConnect3 = new LinkedList<Message>(connects3).getLast();
            advice3 = lastConnect3.getAdvice();
            if (advice3 == null || !advice3.containsKey("multiple-clients"))
            {
                client4 = client3;
                client5 = client2;
                break;
            }
            Thread.sleep(timeout / 10);
        }
        assertNotNull(client4);

        // Disconnect this client normally, the last client must switch to long poll
        client4.disconnect();
        assertTrue(client4.waitFor(1000, BayeuxClient.State.DISCONNECTED));

        // Be sure the last client had the time to switch to long poll mode
        Thread.sleep(timeout + multiSessionInterval);
        Message lastConnect;
        if (client5 == client2)
            lastConnect = new LinkedList<Message>(connects2).getLast();
        else
            lastConnect = new LinkedList<Message>(connects3).getLast();
        Map<String, Object> advice = lastConnect.getAdvice();
        assertTrue(advice == null || !advice.containsKey("multiple-clients"));

        client5.disconnect();
        assertTrue(client5.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }
}
