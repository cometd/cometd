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
package org.cometd.websocket;

import java.util.Arrays;
import java.util.Map;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.transport.JSONTransport;
import org.cometd.websocket.client.okhttp.OkHttpWebsocketTransport;
import org.cometd.websocket.server.JettyWebSocketTransport;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
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
    protected static final String WEBSOCKET_JSR356 = "JSR356";
    protected static final String WEBSOCKET_JETTY = "JETTY";
    protected static final String WEBSOCKET_OKHTTP = "OKHTTP";

    @Parameterized.Parameters(name = "{index}: WebSocket implementation: {0}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {WEBSOCKET_JSR356},
                        {WEBSOCKET_JETTY},
                        {WEBSOCKET_OKHTTP}
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
    protected OkHttpClient okHttpClient;
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
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
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

        switch (wsTransportType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                WebSocketServerContainerInitializer.configureContext(context);
                break;
            case WEBSOCKET_JETTY:
                WebSocketUpgradeFilter.configureContext(context);
                break;
            default:
                throw new IllegalArgumentException();
        }

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
            case WEBSOCKET_JSR356:
                wsClientContainer = ContainerProvider.getWebSocketContainer();
                httpClient.addBean(wsClientContainer, true);
                break;
            case WEBSOCKET_JETTY:
                wsClient = new WebSocketClient(httpClient);
                httpClient.addBean(wsClient, true);
                break;
            case WEBSOCKET_OKHTTP:
                okHttpClient = new OkHttpClient();
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
        return new LongPollingTransport(options, httpClient);
    }

    protected ClientTransport newWebSocketTransport(Map<String, Object> options) {
        return newWebSocketTransport(null, options);
    }

    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options) {
        ClientTransport result;
        switch (wsTransportType) {
            case WEBSOCKET_JSR356:
                result = newWebSocketTransport(url, options, wsClientContainer);
                break;
            case WEBSOCKET_JETTY:
                result = newJettyWebSocketTransport(url, options, wsClient);
                break;
            case WEBSOCKET_OKHTTP:
                result = newOkHttpWebSocketTransport(url, options, okHttpClient);
                break;
            default:
                throw new IllegalArgumentException();
        }
        return result;
    }

    protected org.cometd.websocket.client.WebSocketTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new org.cometd.websocket.client.WebSocketTransport(url, options, null, wsContainer);
    }

    protected org.cometd.websocket.client.JettyWebSocketTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new org.cometd.websocket.client.JettyWebSocketTransport(url, options, null, wsClient);
    }

    protected OkHttpWebsocketTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWebsocketTransport(url, options, null, okHttpClient);
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
        if (server != null) {
            server.stop();
        }
    }

    protected void stopClient() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
    }
}
