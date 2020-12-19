/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.server.CometDServlet;
import org.cometd.server.http.JSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OortTest {
    public static List<String> transports() {
        return List.of(WebSocketTransport.class.getName(), JettyWebSocketTransport.class.getName());
    }

    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final List<Server> servers = new ArrayList<>();
    protected final List<Oort> oorts = new ArrayList<>();
    private final List<BayeuxClient> clients = new ArrayList<>();

    protected Server startServer(String serverTransport, int port) throws Exception {
        return startServer(serverTransport, port, new HashMap<>());
    }

    protected Server startServer(String serverTransport, int port, Map<String, String> options) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server_" + servers.size());
        Server server = new Server(serverThreads);
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);

        if (serverTransport.equals(WebSocketTransport.class.getName())) {
            JakartaWebSocketServletContainerInitializer.configure(context, null);
        } else if (serverTransport.equals(JettyWebSocketTransport.class.getName())) {
            JettyWebSocketServletContainerInitializer.configure(context, null);
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
        BayeuxClient client = new BayeuxClient(oort.getURL(), new JettyHttpClientTransport(null, httpClient));
        client.setAttribute(HttpClient.class.getName(), httpClient);
        client.handshake(handshakeFields);
        clients.add(client);
        return client;
    }

    @AfterEach
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

    protected boolean ipv6Available() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress inetAddress = addresses.nextElement();
                    if (inetAddress instanceof Inet6Address) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    protected static class CometJoinedListener implements Oort.CometListener {
        private final CountDownLatch latch;

        public CometJoinedListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void cometJoined(Event event) {
            latch.countDown();
        }
    }

    protected static class CometLeftListener implements Oort.CometListener {
        private final CountDownLatch latch;

        public CometLeftListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void cometLeft(Event event) {
            latch.countDown();
        }
    }

    protected class HalfNetworkDownExtension implements BayeuxServer.Extension {
        private final Oort oort;
        private final AtomicBoolean halfNetworkDown;

        public HalfNetworkDownExtension(Oort oort, AtomicBoolean halfNetworkDown) {
            this.oort = oort;
            this.halfNetworkDown = halfNetworkDown;
        }

        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            return receive(from, message);
        }

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return receive(from, message);
        }

        private boolean receive(ServerSession session, ServerMessage.Mutable message) {
            if (halfNetworkDown.get() && oort.isOort(session)) {
                logger.info("Network down for server receive {}", message);
                return false;
            }
            return true;
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            return snd(message);
        }

        @Override
        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
            return snd(message);
        }

        private boolean snd(ServerMessage.Mutable message) {
            if (halfNetworkDown.get()) {
                // Only drop replies generated by blocking received messages.
                if (message.containsKey(Message.SUCCESSFUL_FIELD) && !message.isSuccessful()) {
                    logger.info("Network down for server send {}", message);
                    return false;
                }
            }
            return true;
        }
    }
}
