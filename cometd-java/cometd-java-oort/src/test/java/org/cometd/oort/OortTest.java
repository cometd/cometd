/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import java.util.HashMap;
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
import org.cometd.server.transport.JSONTransport;
import org.cometd.websocket.server.JettyWebSocketTransport;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.After;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public abstract class OortTest {
    @Parameterized.Parameters(name = "{index}: transport: {0}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {WebSocketTransport.class.getName()},
                        {JettyWebSocketTransport.class.getName()}
                }
        );
    }

    @Rule
    public final TestTracker testName = new TestTracker();
    protected Logger logger = LoggerFactory.getLogger(getClass());
    protected final List<Server> servers = new ArrayList<>();
    protected final List<Oort> oorts = new ArrayList<>();
    private final List<BayeuxClient> clients = new ArrayList<>();
    private final String serverTransport;

    protected OortTest(String serverTransport) {
        this.serverTransport = serverTransport;
    }

    protected Server startServer(int port) throws Exception {
        return startServer(port, new HashMap<String, String>());
    }

    protected Server startServer(int port, Map<String, String> options) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server_" + servers.size());
        Server server = new Server(serverThreads);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);

        if (serverTransport.equals(WebSocketTransport.class.getName())) {
            WebSocketServerContainerInitializer.configureContext(context);
        } else if (serverTransport.equals(JettyWebSocketTransport.class.getName())) {
            WebSocketUpgradeFilter.configureContext(context);
        } else {
            throw new IllegalArgumentException("Unsupported transport " + serverTransport);
        }

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        String transports = serverTransport + "," + JSONTransport.class.getName();
        cometdServletHolder.setInitParameter("transports", transports);
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        for (Map.Entry<String, String> entry : options.entrySet()) {
            cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        server.start();
        String url = "http://localhost:" + connector.getLocalPort() + cometdServletPath;
        server.setAttribute(OortConfigServlet.OORT_URL_PARAM, url);
        BayeuxServer bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        server.setAttribute(BayeuxServer.ATTRIBUTE, bayeux);

        servers.add(server);

        return server;
    }

    protected Oort startOort(Server server) throws Exception {
        String url = (String)server.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        BayeuxServer bayeuxServer = (BayeuxServer)server.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServer.setOption(Server.class.getName(), server);
        Oort oort = new Oort(bayeuxServer, url);
        oort.start();
        oorts.add(oort);
        return oort;
    }

    protected BayeuxClient startClient(Oort oort, Map<String, Object> handshakeFields) throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        BayeuxClient client = new BayeuxClient(oort.getURL(), new LongPollingTransport(null, httpClient));
        client.setAttribute(HttpClient.class.getName(), httpClient);
        client.handshake(handshakeFields);
        clients.add(client);
        return client;
    }

    @After
    public void stop() throws Exception {
        stopClients();
        stopOorts();
        stopServers();
    }

    protected void stopClients() throws Exception {
        for (int i = clients.size() - 1; i >= 0; --i) {
            stopClient(clients.get(i));
        }
    }

    protected void stopClient(BayeuxClient client) throws Exception {
        client.disconnect(1000);
        ((HttpClient)client.getAttribute(HttpClient.class.getName())).stop();
    }

    protected void stopOorts() throws Exception {
        for (int i = oorts.size() - 1; i >= 0; --i) {
            stopOort(oorts.get(i));
        }
    }

    protected void stopOort(Oort oort) throws Exception {
        oort.stop();
    }

    protected void stopServers() throws Exception {
        for (int i = servers.size() - 1; i >= 0; --i) {
            stopServer(servers.get(i));
        }
    }

    protected void stopServer(Server server) throws Exception {
        server.stop();
        server.join();
    }

    protected static class LatchListener implements ClientSessionChannel.MessageListener {
        private final AtomicInteger count = new AtomicInteger();
        private volatile CountDownLatch latch;

        protected LatchListener() {
            this(1);
        }

        protected LatchListener(int counts) {
            reset(counts);
        }

        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (!message.isMeta() || message.isSuccessful()) {
                count.incrementAndGet();
                countDown();
            }
        }

        public void countDown() {
            latch.countDown();
        }

        public boolean await(int timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        public void reset(int counts) {
            count.set(0);
            latch = new CountDownLatch(counts);
        }

        public int count() {
            return count.get();
        }
    }

    protected static class CometJoinedListener extends Oort.CometListener.Adapter {
        private final CountDownLatch latch;

        public CometJoinedListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void cometJoined(Event event) {
            latch.countDown();
        }
    }

    protected static class CometLeftListener extends Oort.CometListener.Adapter {
        private final CountDownLatch latch;

        public CometLeftListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void cometLeft(Event event) {
            latch.countDown();
        }
    }
}
