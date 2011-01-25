package org.cometd.java.annotation;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClientAnnotationProcessorTest
{
    private static Server server;
    private static String cometdURL;
    private static HttpClient httpClient;
    private BayeuxClient bayeuxClient;
    private ClientAnnotationProcessor processor;

    @BeforeClass
    public static void startServer() throws Exception
    {
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

    @Before
    public void init()
    {
        bayeuxClient = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient));
        processor = new ClientAnnotationProcessor(bayeuxClient);
    }

    @After
    public void destroy()
    {
        bayeuxClient.disconnect();
        bayeuxClient.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }

    @Test
    public void testNull() throws Exception
    {
        boolean processed = processor.process(null);
        assertFalse(processed);
    }

    @Test
    public void testNonServiceAnnotatedClass() throws Exception
    {
        class S
        {
            @Session
            private ClientSession session;
        }

        S s = new S();
        boolean processed = processor.process(s);
        assertFalse(processed);
        assertNull(s.session);
    }

    @Test
    public void testInjectClientSessionOnField() throws Exception
    {
        @Service
        class S
        {
            @Session
            private ClientSession session;
        }

        S s = new S();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.session);
    }

    @Test
    public void testInjectClientSessionOnMethod() throws Exception
    {
        @Service
        class S
        {
            private ClientSession session;

            @Session
            private void set(ClientSession session)
            {
                this.session = session;
            }
        }

        S s = new S();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.session);
    }

    @Test
    public void testListenUnlisten() throws Exception
    {
        final AtomicReference<Message> handshakeRef = new AtomicReference<Message>();
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final AtomicReference<Message> connectRef = new AtomicReference<Message>();
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final AtomicReference<Message> disconnectRef = new AtomicReference<Message>();
        final CountDownLatch disconnectLatch = new CountDownLatch(1);

        @Service
        class S
        {
            @Listener(Channel.META_HANDSHAKE)
            private void metaHandshake(Message handshake)
            {
                handshakeRef.set(handshake);
                handshakeLatch.countDown();
            }

            @Listener(Channel.META_CONNECT)
            private void metaConnect(Message connect)
            {
                connectRef.set(connect);
                connectLatch.countDown();
            }

            @Listener(Channel.META_DISCONNECT)
            private void metaDisconnect(Message connect)
            {
                disconnectRef.set(connect);
                disconnectLatch.countDown();
            }
        }

        S s = new S();
        boolean processed = processor.process(s);
        assertTrue(processed);

        bayeuxClient.handshake();
        assertTrue(handshakeLatch.await(1000, TimeUnit.MILLISECONDS));
        Message handshake = handshakeRef.get();
        assertNotNull(handshake);
        assertTrue(handshake.isSuccessful());

        assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));
        Message connect = connectRef.get();
        assertNotNull(connect);
        assertTrue(connect.isSuccessful());

        processed = processor.deprocessCallbacks(s);
        assertTrue(processed);

        // Listener method must not be notified, since we have deconfigured
        bayeuxClient.disconnect();
        assertFalse(disconnectLatch.await(1000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testSubscribeUnsubscribe() throws Exception
    {
        final AtomicReference<Message> messageRef = new AtomicReference<Message>();
        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));

        @Service
        class S
        {
            @Subscription("/foo")
            private void foo(Message message)
            {
                messageRef.set(message);
                messageLatch.get().countDown();
            }
        }

        S s = new S();
        boolean processed = processor.process(s);
        assertTrue(processed);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        bayeuxClient.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                subscribeLatch.countDown();
            }
        });

        bayeuxClient.handshake();
        assertTrue(bayeuxClient.waitFor(1000, BayeuxClient.State.CONNECTED));
        assertTrue(subscribeLatch.await(1000, TimeUnit.MILLISECONDS));

        bayeuxClient.getChannel("/foo").publish(new HashMap());
        assertTrue(messageLatch.get().await(1000, TimeUnit.MILLISECONDS));

        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        bayeuxClient.getChannel(Channel.META_UNSUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                unsubscribeLatch.countDown();
            }
        });

        processor.deprocessCallbacks(s);
        assertTrue(unsubscribeLatch.await(1000, TimeUnit.MILLISECONDS));

        messageLatch.set(new CountDownLatch(1));

        bayeuxClient.getChannel("/foo").publish(new HashMap());
        assertFalse(messageLatch.get().await(1000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testUsage() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>();

        @Service
        class S
        {
            private boolean initialized;
            private boolean connected;

            @Session
            private ClientSession session;

            @PostConstruct
            private void init()
            {
                initialized = true;
            }

            @PreDestroy
            private void destroy()
            {
                initialized = false;
            }

            @Listener(Channel.META_CONNECT)
            public void metaConnect(Message connect)
            {
                connected = connect.isSuccessful();
                connectLatch.countDown();
            }

            @Subscription("/foo")
            public void foo(Message message)
            {
                messageLatch.get().countDown();
            }
        }

        S s = new S();
        processor.process(s);
        assertTrue(s.initialized);
        assertFalse(s.connected);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        bayeuxClient.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                subscribeLatch.countDown();
            }
        });

        bayeuxClient.handshake();
        assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(s.connected);
        assertTrue(subscribeLatch.await(1000, TimeUnit.MILLISECONDS));

        messageLatch.set(new CountDownLatch(1));
        bayeuxClient.getChannel("/foo").publish(new HashMap());
        assertTrue(messageLatch.get().await(1000, TimeUnit.MILLISECONDS));

        processor.deprocess(s);
        assertFalse(s.initialized);

        messageLatch.set(new CountDownLatch(1));
        bayeuxClient.getChannel("/foo").publish(new HashMap());
        assertFalse(messageLatch.get().await(1000, TimeUnit.MILLISECONDS));
    }
}
