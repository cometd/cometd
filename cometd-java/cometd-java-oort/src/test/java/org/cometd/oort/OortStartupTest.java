/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class OortStartupTest {
    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    private final Map<Integer, Server> servers = new ConcurrentHashMap<>();
    private Map<Integer, ServletContextHandler> contexts = new ConcurrentHashMap<>();

    protected int[] startTwoNodes(Class<? extends HttpServlet> startupServletClass, Map<String, String> options) throws Exception {
        int port1;
        try (ServerSocket server1 = new ServerSocket(0)) {
            port1 = server1.getLocalPort();
        }
        int port2;
        try (ServerSocket server2 = new ServerSocket(0)) {
            port2 = server2.getLocalPort();
        }

        startNode(startupServletClass, options, port1, port2);
        startNode(startupServletClass, options, port2, port1);

        return new int[]{port1, port2};
    }

    private void startNode(Class<? extends HttpServlet> startupServletClass, Map<String, String> options, int port1, int port2) throws Exception {
        Server server = new Server();
        servers.put(port1, server);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port1);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        contexts.put(port1, context);

        WebSocketServerContainerInitializer.configureContext(context);

        // CometD servlet.
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        // Oort servlet.
        ServletHolder oortServletHolder = new ServletHolder(OortStaticConfigServlet.class);
        oortServletHolder.setInitParameter("oort.url", "http://localhost:" + port1 + cometdServletPath);
        oortServletHolder.setInitParameter("oort.cloud", "http://localhost:" + port2 + cometdServletPath);
        oortServletHolder.setInitOrder(2);
        context.addServlet(oortServletHolder, "/no_mapping_1");

        // Startup servlet.
        if (startupServletClass != null) {
            ServletHolder startupServletHolder = new ServletHolder(startupServletClass);
            startupServletHolder.setInitOrder(3);
            context.addServlet(startupServletHolder, "/no_mapping_2");
        }

        server.start();
    }

    @Test
    public void testTwoNodeStartupOneNodeRestartWithinMaxInterval() throws Exception {
        long maxInterval = 8000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        int[] ports = startTwoNodes(OortObjectStartupServlet.class, options);
        int port1 = ports[0];
        int port2 = ports[1];
        ContextHandler.Context context1 = contexts.get(port1).getServletContext();
        Oort oort1 = (Oort)context1.getAttribute(Oort.OORT_ATTRIBUTE);
        ContextHandler.Context context2 = contexts.get(port2).getServletContext();
        Oort oort2 = (Oort)context2.getAttribute(Oort.OORT_ATTRIBUTE);

        // Wait for the startup to finish.
        Thread.sleep(1000);

        OortComet oortComet12 = oort1.findComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Kill one node.
        Server server2 = servers.get(port2);
        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        connector2.stop();
        // Break connectivity to avoid graceful shutdown when stopping the comet.
        Server server1 = servers.get(port1);
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        connector1.stop();
        // Stop the node.
        server2.stop();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Wait to let the comets disconnect, but less than maxInterval.
        Thread.sleep(maxInterval / 2);

        final String oldOortId2 = oort2.getId();

        // Restore the connectivity.
        connector1.setPort(port1);
        connector1.start();

        // Restart the node.
        startNode(OortObjectStartupServlet.class, null, port2, port1);
        context2 = contexts.get(port2).getServletContext();
        oort2 = (Oort)context2.getAttribute(Oort.OORT_ATTRIBUTE);

        // Wait for the restart to finish.
        Thread.sleep(1000);

        oortComet21 = oort2.findComet(oort1.getURL());

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the OortObject to sync.
        Thread.sleep(1000);

        @SuppressWarnings("unchecked")
        OortObject<String> oortObject1 = (OortObject<String>)context1.getAttribute(OortObjectStartupServlet.NAME);
        @SuppressWarnings("unchecked")
        OortObject<String> oortObject2 = (OortObject<String>)context2.getAttribute(OortObjectStartupServlet.NAME);
        Assert.assertEquals(2, oortObject1.getInfos().size());
        Assert.assertEquals(2, oortObject2.getInfos().size());

        Assert.assertNotEquals(oldOortId2, oortObject1.getInfo(oort2.getURL()).getObject());
    }

    public static class OortObjectStartupServlet extends HttpServlet {
        private static final String NAME = "counter";
        private OortObject<String> ids;

        @Override
        public void init() throws ServletException {
            try {
                Oort oort = (Oort)getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
                ids = new OortObject<>(oort, NAME, OortObjectFactories.forString(""));
                getServletContext().setAttribute(NAME, ids);
                ids.start();
                ids.setAndShare(oort.getId(), null);
            } catch (Throwable x) {
                throw new ServletException(x);
            }
        }
    }
}
