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
package org.cometd.server.websocket;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.transport.JSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public abstract class ClientServerWebSocketTest {
    protected static final String WEBSOCKET_JSR_356 = "JSR 356";
    protected static final String WEBSOCKET_JETTY = "JETTY";

    @Parameterized.Parameters(name = "{index}: WebSocket implementation: {0}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {WEBSOCKET_JSR_356},
                        {WEBSOCKET_JETTY}
                }
        );
    }

    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final String wsTransportType;
    protected ServerConnector connector;
    protected Server server;
    protected ServletContextHandler context;
    protected String cometdServletPath;
    protected HttpClient httpClient;
    protected WebSocketContainer wsClientContainer;
    protected WebSocketClient wsClient;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;

    protected ClientServerWebSocketTest(String wsTransportType) {
        this.wsTransportType = wsTransportType;
    }

    protected void prepareAndStart(Map<String, String> initParams) throws Exception {
        prepareAndStart("/cometd", initParams);
    }

    protected void prepareAndStart(String servletPath, Map<String, String> initParams) throws Exception {
        prepareServer(0, servletPath, initParams, true);
        prepareClient();
        startServer();
        startClient();
    }

    protected void prepareServer(int port, Map<String, String> initParams, boolean eager) throws Exception {
        prepareServer(port, "/cometd", initParams, eager);
    }

    protected void prepareServer(int port, String servletPath, Map<String, String> initParams, boolean eager) throws Exception {
        String wsTransportClass;
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                wsTransportClass = WebSocketTransport.class.getName();
                break;
            case WEBSOCKET_JETTY:
                wsTransportClass = JettyWebSocketTransport.class.getName();
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareServer(port, servletPath, initParams, eager, wsTransportClass);
    }

    protected void prepareServer(int port, String servletPath, Map<String, String> initParams, boolean eager, String wsTransportClass) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        context = new ServletContextHandler(server, "/", true, false);

        ServletContainerInitializer initializer;
        if (WEBSOCKET_JSR_356.equals(wsTransportType)) {
            initializer = new JavaxWebSocketServletContainerInitializer();
        } else if (WEBSOCKET_JETTY.equals(wsTransportType)) {
            initializer = new JettyWebSocketServletContainerInitializer();
        } else {
            throw new IllegalArgumentException("Unsupported transport " + wsTransportType);
        }

        context.addEventListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent sce) {
                try {
                    initializer.onStartup(Set.of(), sce.getServletContext());
                } catch (ServletException x) {
                    throw new RuntimeException(x);
                }
            }
        });

        // CometD servlet
        cometdServletPath = servletPath;
        if (cometdServletPath.endsWith("/*")) {
            cometdServletPath = cometdServletPath.substring(0, cometdServletPath.length() - 2);
        }
        String cometdURLMapping = cometdServletPath;
        if (!cometdURLMapping.endsWith("/*")) {
            cometdURLMapping = cometdURLMapping + "/*";
        }
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        String transports = wsTransportClass + "," + JSONTransport.class.getName();
        cometdServletHolder.setInitParameter("transports", transports);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (eager) {
            cometdServletHolder.setInitOrder(1);
        }
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        context.addServlet(cometdServletHolder, cometdURLMapping);
    }

    protected void prepareClient() {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient();
        httpClient.setExecutor(clientThreads);
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                wsClientContainer = ContainerProvider.getWebSocketContainer();
                httpClient.addBean(wsClientContainer, true);
                break;
            case WEBSOCKET_JETTY:
                wsClient = new WebSocketClient(httpClient);
                httpClient.addBean(wsClient, true);
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    protected void startServer() throws Exception {
        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + cometdServletPath;
        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    protected void startClient() throws Exception {
        httpClient.start();
    }

    protected BayeuxClient newBayeuxClient() {
        return new BayeuxClient(cometdURL, newWebSocketTransport(null));
    }

    protected ClientTransport newLongPollingTransport(Map<String, Object> options) {
        return new JettyHttpClientTransport(options, httpClient);
    }

    protected ClientTransport newWebSocketTransport(Map<String, Object> options) {
        return newWebSocketTransport(null, options);
    }

    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options) {
        ClientTransport result;
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                result = new org.cometd.client.websocket.javax.WebSocketTransport(url, options, null, wsClientContainer);
                break;
            case WEBSOCKET_JETTY:
                result = new org.cometd.client.websocket.jetty.JettyWebSocketTransport(url, options, null, wsClient);
                break;
            default:
                throw new IllegalArgumentException();
        }
        return result;
    }

    protected void disconnectBayeuxClient(BayeuxClient client) {
        client.disconnect(1000);
    }

    @After
    public void stopAndDispose() throws Exception {
        stopClient();
        stopServer();
    }

    protected void stopServer() throws Exception {
        server.stop();
        server.join();
    }

    protected void stopClient() throws Exception {
        httpClient.stop();
    }
}
