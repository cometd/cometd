/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.stream.Stream;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.http.okhttp.OkHttpClientTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.http.AsyncJSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class OortStartupTest {
    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    private final Map<Integer, Server> servers = new ConcurrentHashMap<>();
    private final Map<Integer, ServletContextHandler> contexts = new ConcurrentHashMap<>();

    protected int[] startTwoNodes(Class<? extends HttpServlet> startupServletClass, Map<String, String> cometdOptions, Map<String, String> oortOptions) throws Exception {
        int port1;
        try (ServerSocket server1 = new ServerSocket(0)) {
            port1 = server1.getLocalPort();
        }
        int port2;
        try (ServerSocket server2 = new ServerSocket(0)) {
            port2 = server2.getLocalPort();
        }

        startNode(startupServletClass, cometdOptions, oortOptions, port1, port2);
        startNode(startupServletClass, cometdOptions, oortOptions, port2, port1);

        return new int[]{port1, port2};
    }

    private void startNode(Class<? extends HttpServlet> startupServletClass, Map<String, String> cometdOptions, Map<String, String> oortOptions, int port1, int port2) throws Exception {
        Server server = new Server();
        servers.put(port1, server);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port1);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        contexts.put(port1, context);

        JakartaWebSocketServletContainerInitializer.configure(context, null);

        // CometD servlet.
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (cometdOptions != null) {
            for (Map.Entry<String, String> entry : cometdOptions.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        // Oort servlet.
        ServletHolder oortServletHolder = new ServletHolder(OortStaticConfigServlet.class);
        oortServletHolder.setInitParameter("oort.url", "http://localhost:" + port1 + cometdServletPath);
        oortServletHolder.setInitParameter("oort.cloud", "http://localhost:" + port2 + cometdServletPath);
        if (oortOptions != null) {
            for (Map.Entry<String, String> entry : oortOptions.entrySet()) {
                oortServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
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
        Map<String, String> cometdOptions = new HashMap<>();
        cometdOptions.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        int[] ports = startTwoNodes(OortObjectStartupServlet.class, cometdOptions, null);
        int port1 = ports[0];
        int port2 = ports[1];
        ServletContext context1 = contexts.get(port1).getServletContext();
        Oort oort1 = (Oort)context1.getAttribute(Oort.OORT_ATTRIBUTE);
        ServletContext context2 = contexts.get(port2).getServletContext();
        Oort oort2 = (Oort)context2.getAttribute(Oort.OORT_ATTRIBUTE);

        // Wait for the startup to finish.
        Thread.sleep(1000);

        OortComet oortComet12 = oort1.findComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

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

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Wait to let the comets disconnect, but less than maxInterval.
        Thread.sleep(maxInterval / 2);

        String oldOortId2 = oort2.getId();

        // Restore the connectivity.
        connector1.setPort(port1);
        connector1.start();

        // Restart the node.
        startNode(OortObjectStartupServlet.class, null, null, port2, port1);
        context2 = contexts.get(port2).getServletContext();
        oort2 = (Oort)context2.getAttribute(Oort.OORT_ATTRIBUTE);

        // Wait for the restart to finish.
        Thread.sleep(1000);

        oortComet21 = oort2.findComet(oort1.getURL());

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the OortObject to sync.
        Thread.sleep(1000);

        @SuppressWarnings("unchecked")
        OortObject<String> oortObject1 = (OortObject<String>)context1.getAttribute(OortObjectStartupServlet.NAME);
        @SuppressWarnings("unchecked")
        OortObject<String> oortObject2 = (OortObject<String>)context2.getAttribute(OortObjectStartupServlet.NAME);
        Assertions.assertEquals(2, oortObject1.getInfos().size());
        Assertions.assertEquals(2, oortObject2.getInfos().size());

        Assertions.assertNotEquals(oldOortId2, oortObject1.getInfo(oort2.getURL()).getObject());
    }

    public static Stream<Arguments> transports() {
        return Stream.of(
                Arguments.of(WebSocketTransport.class.getName(), org.cometd.client.websocket.javax.WebSocketTransport.Factory.class.getName()),
                Arguments.of(WebSocketTransport.class.getName(), OkHttpWebSocketTransport.Factory.class.getName()),
                Arguments.of(AsyncJSONTransport.class.getName(), JettyHttpClientTransport.Factory.class.getName()),
                Arguments.of(AsyncJSONTransport.class.getName(), OkHttpClientTransport.Factory.class.getName())
        );
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testTwoNodeStartupCustomJSONContext(String serverTransport, String clientTransportFactory) throws Exception {
        Map<String, String> cometdOptions = new HashMap<>();
        cometdOptions.put(BayeuxServerImpl.TRANSPORTS_OPTION, serverTransport);
        cometdOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, CustomJSONContextServer.class.getName());
        Map<String, String> oortOptions = new HashMap<>();
        oortOptions.put(OortConfigServlet.OORT_CLIENT_TRANSPORT_FACTORIES_PARAM, clientTransportFactory);
        oortOptions.put(OortConfigServlet.OORT_JSON_CONTEXT_PARAM, CustomJSONContextClient.class.getName());

        int[] ports = startTwoNodes(OortMapStartupServlet.class, cometdOptions, oortOptions);
        int port1 = ports[0];
        int port2 = ports[1];
        ServletContext context1 = contexts.get(port1).getServletContext();
        Oort oort1 = (Oort)context1.getAttribute(Oort.OORT_ATTRIBUTE);
        ServletContext context2 = contexts.get(port2).getServletContext();
        Oort oort2 = (Oort)context2.getAttribute(Oort.OORT_ATTRIBUTE);

        // Wait for the startup to finish.
        Thread.sleep(1000);

        OortComet oortComet12 = oort1.findComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // One node creates a new UserInfo.
        @SuppressWarnings("unchecked")
        OortStringMap<UserInfo> oortMap1 = (OortStringMap<UserInfo>)context1.getAttribute(OortMapStartupServlet.NAME);
        String key = "user1";
        UserInfo userInfo1 = new UserInfo("userId1");
        oortMap1.putAndShare(key, userInfo1, null);

        // Wait for the value to propagate.
        Thread.sleep(1000);

        // The other node should have the UserInfo.
        @SuppressWarnings("unchecked")
        OortStringMap<UserInfo> oortMap2 = (OortStringMap<UserInfo>)context2.getAttribute(OortMapStartupServlet.NAME);
        UserInfo userInfo2 = oortMap2.find(key);
        assertNotNull(userInfo2);
        assertEquals(userInfo1.getUserId(), userInfo2.getUserId());
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

    public static class OortMapStartupServlet extends HttpServlet {
        private static final String NAME = "users";
        private OortStringMap<UserInfo> users;

        @Override
        public void init() throws ServletException {
            try {
                Oort oort = (Oort)getServletContext().getAttribute(Oort.OORT_ATTRIBUTE);
                users = new OortStringMap<>(oort, NAME, OortObjectFactories.forConcurrentMap());
                getServletContext().setAttribute(NAME, users);
                users.start();
            } catch (Throwable x) {
                throw new ServletException(x);
            }
        }
    }

    public static class UserInfo {
        private final String userId;

        public UserInfo(String userId) {
            this.userId = userId;
        }

        public String getUserId() {
            return userId;
        }
    }

    public static class UserInfoConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output output) {
            System.err.println("toJSON() = " + obj);
            UserInfo userInfo = (UserInfo)obj;
            output.addClass(UserInfo.class);
            output.add("userId", userInfo.getUserId());
        }

        @Override
        public Object fromJSON(Map map) {
            System.err.println("fromJSON() = " + map);
            String userId = (String)map.get("userId");
            return new UserInfo(userId);
        }
    }

    public static class CustomJSONContextServer extends JettyJSONContextServer {
        public CustomJSONContextServer() {
            putConvertor(UserInfo.class.getName(), new UserInfoConvertor());
        }
    }

    public static class CustomJSONContextClient extends JettyJSONContextClient {
        public CustomJSONContextClient() {
            putConvertor(UserInfo.class.getName(), new UserInfoConvertor());
        }
    }
}
