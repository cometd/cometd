/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import java.util.List;
import java.util.Map;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.servlet.CometDServlet;
import org.cometd.server.servlet.transport.ServletJSONTransport;
import org.cometd.server.websocket.jakarta.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientServerWebSocketTest {
    protected static final String WEBSOCKET_JAKARTA = "JAKARTA";
    protected static final String WEBSOCKET_JETTY = "JETTY";
    protected static final String WEBSOCKET_OKHTTP = "OKHTTP";

    public static List<String> wsTypes() {
        return List.of(WEBSOCKET_JAKARTA, WEBSOCKET_JETTY, WEBSOCKET_OKHTTP);
    }

    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected final Logger logger = LoggerFactory.getLogger(getClass());
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

    protected void prepareAndStart(String wsType, Map<String, String> initParams) throws Exception {
        prepareAndStart(wsType, "/cometd", initParams);
    }

    protected void prepareAndStart(String wsType, String servletPath, Map<String, String> initParams) throws Exception {
        prepareServer(wsType, 0, servletPath, initParams);
        prepareClient(wsType);
        startServer();
        startClient();
    }

    protected void prepareServer(String wsType, int port) {
        prepareServer(wsType, port, "/cometd", null);
    }

    protected void prepareServer(String wsType, Map<String, String> initParams) {
        prepareServer(wsType, 0, "/cometd", initParams);
    }

    protected void prepareServer(String wsType, int port, String servletPath, Map<String, String> initParams) {
        String wsTransportClass = switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> WebSocketTransport.class.getName();
            case WEBSOCKET_JETTY -> JettyWebSocketTransport.class.getName();
            default -> throw new IllegalArgumentException();
        };
        prepareServer(wsType, port, servletPath, initParams, wsTransportClass);
    }

    protected void prepareServer(String wsType, int port, String servletPath, Map<String, String> initParams, String wsTransportClass) {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, 1, 1);
        connector.setPort(port);
        server.addConnector(connector);

        context = new ServletContextHandler("/", true, false);
        server.setHandler(context);

        switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP ->
                    JakartaWebSocketServletContainerInitializer.configure(context, null);
            // TODO: use WSUH?
//            case WEBSOCKET_JETTY -> JettyWebSocketServletContainerInitializer.configure(context, null);
            default -> throw new IllegalArgumentException("Unsupported transport " + wsType);
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
        String transports = wsTransportClass + "," + ServletJSONTransport.class.getName();
        cometdServletHolder.setInitParameter("transports", transports);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        cometdServletHolder.setInitOrder(1);
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        context.addServlet(cometdServletHolder, cometdURLMapping);
    }

    protected void prepareClient(String wsType) {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient(new HttpClientTransportOverHTTP(1));
        httpClient.setExecutor(clientThreads);
        switch (wsType) {
            case WEBSOCKET_JAKARTA -> {
                wsClientContainer = ContainerProvider.getWebSocketContainer();
                httpClient.addBean(wsClientContainer, true);
            }
            case WEBSOCKET_JETTY -> {
                wsClient = new WebSocketClient(httpClient);
                httpClient.addBean(wsClient, true);
            }
            case WEBSOCKET_OKHTTP -> okHttpClient = new OkHttpClient();
            default -> throw new IllegalArgumentException();
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

    protected BayeuxClient newBayeuxClient(String wsType) {
        return new BayeuxClient(cometdURL, newWebSocketTransport(wsType, null));
    }

    protected ClientTransport newLongPollingTransport(Map<String, Object> options) {
        return new JettyHttpClientTransport(options, httpClient);
    }

    protected ClientTransport newWebSocketTransport(String wsType, Map<String, Object> options) {
        return newWebSocketTransport(wsType, null, options);
    }

    protected ClientTransport newWebSocketTransport(String wsType, String url, Map<String, Object> options) {
        return switch (wsType) {
            case WEBSOCKET_JAKARTA -> newWebSocketTransport(url, options, wsClientContainer);
            case WEBSOCKET_JETTY -> newJettyWebSocketTransport(url, options, wsClient);
            case WEBSOCKET_OKHTTP -> newOkHttpWebSocketTransport(url, options, okHttpClient);
            default -> throw new IllegalArgumentException();
        };
    }

    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new org.cometd.client.websocket.jakarta.WebSocketTransport(url, options, null, wsContainer);
    }

    protected ClientTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new org.cometd.client.websocket.jetty.JettyWebSocketTransport(url, options, null, wsClient);
    }

    protected ClientTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWebSocketTransport(url, options, null, okHttpClient);
    }

    protected void disconnectBayeuxClient(BayeuxClient client) {
        client.disconnect(1000);
    }

    @AfterEach
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
