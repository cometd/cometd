/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.oort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public abstract class OortTest
{
    @Rule
    public final TestWatcher testName = new TestWatcher()
    {
        @Override
        protected void starting(Description description)
        {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getClassName(), description.getMethodName());
        }
    };
    private final List<Server> servers = new ArrayList<Server>();
    private final List<Oort> oorts = new ArrayList<Oort>();
    private final List<BayeuxClient> clients = new ArrayList<BayeuxClient>();

    protected Server startServer(int port) throws Exception
    {
        Server server = new Server();
        Connector connector = new SelectChannelConnector();
        connector.setPort(port);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("transports", WebSocketTransport.class.getName());
        if (Boolean.getBoolean("debugTests"))
            cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        String url = "http://localhost:" + connector.getLocalPort() + contextPath + cometdServletPath;
        server.setAttribute(OortConfigServlet.OORT_URL_PARAM, url);
        BayeuxServer bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        server.setAttribute(BayeuxServer.ATTRIBUTE, bayeux);

        servers.add(server);

        return server;
    }

    protected Oort startOort(Server server) throws Exception
    {
        String url = (String)server.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        final BayeuxServer bayeuxServer = (BayeuxServer)server.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort = new Oort(bayeuxServer, url);
        oort.setClientDebugEnabled(Boolean.getBoolean("debugTests"));
        oort.start();
        oorts.add(oort);
        return oort;
    }

    protected BayeuxClient startClient(Oort oort, Map<String, Object> handshakeFields)
    {
        BayeuxClient client = new BayeuxClient(oort.getURL(), new LongPollingTransport(null, oort.getHttpClient()));
        client.setDebugEnabled(Boolean.getBoolean("debugTests"));
        client.handshake(handshakeFields);
        client.waitFor(5000, BayeuxClient.State.CONNECTED);
        clients.add(client);
        return client;
    }

    @After
    public void stop() throws Exception
    {
        stopClients();
        stopOorts();
        stopServers();
    }

    protected void stopClients()
    {
        for (int i = clients.size() - 1; i >= 0; --i)
            stopClient(clients.get(i));
    }

    protected void stopClient(BayeuxClient client)
    {
        client.disconnect(1000);
    }

    protected void stopOorts() throws Exception
    {
        for (int i = oorts.size() - 1; i >= 0; --i)
            stopOort(oorts.get(i));
    }

    protected void stopOort(Oort oort) throws Exception
    {
        oort.stop();
    }

    protected void stopServers() throws Exception
    {
        for (int i = servers.size() - 1; i >= 0; --i)
            stopServer(servers.get(i));
    }

    protected void stopServer(Server server) throws Exception
    {
        server.stop();
        server.join();
    }

    protected static class LatchListener implements ClientSessionChannel.MessageListener
    {
        private final AtomicInteger count = new AtomicInteger();
        private volatile CountDownLatch latch;

        protected LatchListener()
        {
            this(1);
        }

        protected LatchListener(int counts)
        {
            reset(counts);
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (!message.isMeta() || message.isSuccessful())
            {
                count.incrementAndGet();
                countDown();
            }
        }

        public void countDown()
        {
            latch.countDown();
        }

        public boolean await(int timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }

        public void reset(int counts)
        {
            count.set(0);
            latch = new CountDownLatch(counts);
        }

        public int count()
        {
            return count.get();
        }
    }

    protected static class CometJoinedListener extends Oort.CometListener.Adapter
    {
        private final CountDownLatch latch;

        public CometJoinedListener(CountDownLatch latch)
        {
            this.latch = latch;
        }

        public void cometJoined(Event event)
        {
            latch.countDown();
        }
    }

    protected static class CometLeftListener extends Oort.CometListener.Adapter
    {
        private final CountDownLatch latch;

        public CometLeftListener(CountDownLatch latch)
        {
            this.latch = latch;
        }

        public void cometLeft(Event event)
        {
            latch.countDown();
        }
    }
}
