package org.cometd.client;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;

public class SimulatedNetworkFailureTest extends TestCase
{
    private Server server;
    private String cometdURL;
    private HttpClient httpClient;
    private long timeout = 10000;
    private long maxInterval = 8000;
    private long sweepInterval = 1000;

    @Override
    protected void setUp() throws Exception
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
        cometdServletHolder.setInitParameter("maxInterval", String.valueOf(maxInterval));
        cometdServletHolder.setInitParameter("sweepIntervalMs", String.valueOf(sweepInterval));
        cometdServletHolder.setInitParameter("logLevel", "3");

        String servletPath = "/cometd";
        context.addServlet(cometdServletHolder, servletPath + "/*");

        server.start();

        cometdURL = "http://localhost:" + connector.getLocalPort() + contextPath + servletPath;

        httpClient = new HttpClient();
        httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        httpClient.stop();

        server.stop();
        server.join();
    }

    public void testShortNetworkFailure() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>();
        final AtomicBoolean connected = new AtomicBoolean(false);

        TestBayeuxClient client = new TestBayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                boolean wasConnected = connected.get();
                connected.set(message.isSuccessful());

                if (!wasConnected && connected.get())
                    Log.info("BayeuxClient connected {}", message);
                else if (wasConnected && !connected.get())
                    Log.info("BayeuxClient unconnected {}", message);
            }
        });
        String channelName = "/test";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.get().countDown();
            }
        });

        client.handshake();
        assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));

        long networkDown = maxInterval / 2;
        client.setNetworkDown(networkDown);

        // Wait for a while then publish, it must succeed
        Thread.sleep(1000);
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertTrue(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        // Wait for the connect to return
        // We already slept a bit before, so we are sure that the connect returned
        Thread.sleep(timeout);

        // Now we are simulating the network is down
        // Be sure we are disconnected
        assertFalse(connected.get());

        // Another publish, it must fail
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertTrue(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        // Sleep to allow the next connect to be issued
        Thread.sleep(networkDown);

        // Now another connect has been sent (delayed by 'networkDown' ms)
        Thread.sleep(timeout);
        assertTrue(connected.get());

        // We should be able to publish now
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertFalse(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    public void testLongNetworkFailure() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final CountDownLatch handshakeLatch = new CountDownLatch(2);
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>();
        final AtomicBoolean connected = new AtomicBoolean(false);

        TestBayeuxClient client = new TestBayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    handshakeLatch.countDown();
            }
        });
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                boolean wasConnected = connected.get();
                connected.set(message.isSuccessful());

                if (!wasConnected && connected.get())
                    Log.info("BayeuxClient connected");
                else if (wasConnected && !connected.get())
                    Log.info("BayeuxClient unconnected");
            }
        });
        String channelName = "/test";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.get().countDown();
            }
        });
        client.handshake();
        assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));

        // Add some margin since the session is swept every 'sweepIntervalMs'
        long networkDown = maxInterval + 3 * sweepInterval;
        client.setNetworkDown(networkDown);

        // Wait for a while then publish, it must succeed
        Thread.sleep(1000);
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertTrue(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        // Wait for the connect to return
        // We already slept a bit before, so we are sure that the connect returned
        Thread.sleep(timeout);

        // Now we are simulating the network is down
        // Be sure we are disconnected
        assertFalse(connected.get());

        // Another publish, it must fail
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertTrue(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        // Sleep to allow the next connect to be issued
        Thread.sleep(networkDown);

        // Now another connect has been sent (delayed by 'networkDown' ms)
        // but the server expired the client, so we handshake again
        assertTrue(handshakeLatch.await(1000, TimeUnit.MILLISECONDS));

        // We should be able to publish now
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap());
        assertFalse(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    private class TestBayeuxClient extends BayeuxClient
    {
        private long networkDown;

        private TestBayeuxClient(String url, ClientTransport transport)
        {
            super(url, transport);
        }

        public void setNetworkDown(long time)
        {
            this.networkDown = time;
            Log.info("Set network down");
        }

        @Override
        protected void processConnect(Message.Mutable connect)
        {
            if (networkDown > 0)
                connect.setSuccessful(false);
            super.processConnect(connect);
        }

        @Override
        protected boolean scheduleConnect(long interval, long backoff)
        {
            if (networkDown > 0)
                backoff = networkDown;
            return super.scheduleConnect(interval, backoff);
        }

        @Override
        protected boolean sendConnect()
        {
            if (networkDown > 0)
            {
                networkDown = 0;
                Log.info("Reset network down");
            }
            return super.sendConnect();
        }

        @Override
        protected void enqueueSend(Message.Mutable message)
        {
            if (networkDown > 0)
                failMessages(null, message);
            else
                super.enqueueSend(message);
        }
    }
}
