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
import java.util.Arrays;
import java.util.Collections;
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
import org.cometd.server.CometDServlet;
import org.cometd.websocket.server.JettyWebSocketTransport;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.jsr356.server.WebSocketConfiguration;
import org.junit.After;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class OortTest
{
    @Parameterized.Parameters(name= "{index}: transport: {0}")
     public static Iterable<Object[]> data()
     {
         return Arrays.asList(new Object[][]
                 {
                         {WebSocketTransport.class.getName()},
                         {JettyWebSocketTransport.class.getName()}
                 }
         );
     }

    @Rule
    public final TestTracker testName = new TestTracker();
    private final List<Server> servers = new ArrayList<>();
    private final List<Oort> oorts = new ArrayList<>();
    private final List<BayeuxClient> clients = new ArrayList<>();
    private final String serverTransport;

    protected OortTest(String serverTransport)
    {
        this.serverTransport = serverTransport;
    }

    protected Server startServer(int port) throws Exception
    {
        return startServer(port, Collections.<String, String>emptyMap());
    }

    protected Server startServer(int port, Map<String, String> options) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath, ServletContextHandler.SESSIONS);

        WebSocketConfiguration.configureContext(context);

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("transports", serverTransport);
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (Boolean.getBoolean("debugTests"))
            cometdServletHolder.setInitParameter("logLevel", "3");
        for (Map.Entry<String, String> entry : options.entrySet())
            cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

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
