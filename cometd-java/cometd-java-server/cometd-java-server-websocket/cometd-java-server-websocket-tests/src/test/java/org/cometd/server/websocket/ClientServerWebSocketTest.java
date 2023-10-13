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

import java.util.HashMap;
import java.util.Map;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.http.JSONHttpTransport;
import org.cometd.server.http.jakarta.CometDServlet;
import org.cometd.server.http.jetty.CometDHandler;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.cometd.server.websocket.jakarta.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientServerWebSocketTest {
    public static Transport[] transports() {
        return Transport.values();
    }

    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected ServerConnector connector;
    protected Server server;
    protected ContextHandler context;
    protected String cometdPath;
    protected HttpClient httpClient;
    protected WebSocketContainer wsClientContainer;
    protected WebSocketClient wsClient;
    protected OkHttpClient okHttpClient;
    protected String cometdURL;
    protected BayeuxServer bayeuxServer;

    protected void prepareAndStart(Transport wsType, Map<String, String> options) throws Exception {
        prepareAndStart(wsType, "/cometd", options);
    }

    protected void prepareAndStart(Transport wsType, String cometdPath, Map<String, String> options) throws Exception {
        prepareServer(wsType, 0, cometdPath, options);
        prepareClient(wsType);
        startServer();
        startClient();
    }

    protected void prepareServer(Transport wsType, int port) {
        prepareServer(wsType, port, "/cometd", null);
    }

    protected void prepareServer(Transport wsType, Map<String, String> options) {
        prepareServer(wsType, 0, "/cometd", options);
    }

    protected void prepareServer(Transport wsType, int port, String cometdPath, Map<String, String> options) {
        String wsTransportClass = switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> WebSocketTransport.class.getName();
            case WEBSOCKET_JETTY -> JettyWebSocketTransport.class.getName();
        };
        prepareServer(wsType, port, cometdPath, options, wsTransportClass);
    }

    protected void prepareServer(Transport wsType, int port, String cometdPath, Map<String, String> options, String wsTransportClass) {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, 1, 1);
        connector.setPort(port);
        server.addConnector(connector);

        if (cometdPath.endsWith("/*")) {
            cometdPath = cometdPath.substring(0, cometdPath.length() - 2);
        }
        this.cometdPath = cometdPath;
        String cometdURLMapping = cometdPath + "/*";

        switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> {
                ServletContextHandler servletContext = new ServletContextHandler("/");
                server.setHandler(servletContext);
                context = servletContext;
                JakartaWebSocketServletContainerInitializer.configure(servletContext, null);
                CometDServlet cometdServlet = new CometDServlet();
                ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
                String transports = wsTransportClass + "," + JSONHttpTransport.class.getName();
                cometdServletHolder.setInitParameter(BayeuxServerImpl.TRANSPORTS_OPTION, transports);
                cometdServletHolder.setInitParameter(AbstractServerTransport.TIMEOUT_OPTION, "10000");
                cometdServletHolder.setInitParameter(AbstractWebSocketTransport.COMETD_URL_MAPPING_OPTION, cometdURLMapping);
                if (options != null) {
                    for (Map.Entry<String, String> entry : options.entrySet()) {
                        cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
                    }
                }
                cometdServletHolder.setInitOrder(1);
                servletContext.addServlet(cometdServletHolder, cometdURLMapping);
            }
            case WEBSOCKET_JETTY -> {
                context = new ContextHandler("/");
                server.setHandler(context);
                WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
                context.setHandler(wsHandler);
                CometDHandler cometdHandler = new CometDHandler();
                if (options == null) {
                    options = new HashMap<>();
                }
                String transports = wsTransportClass + "," + JSONHttpTransport.class.getName();
                options.putIfAbsent(BayeuxServerImpl.TRANSPORTS_OPTION, transports);
                options.putIfAbsent(AbstractServerTransport.TIMEOUT_OPTION, "10000");
                options.putIfAbsent(AbstractWebSocketTransport.COMETD_URL_MAPPING_OPTION, cometdURLMapping);
                cometdHandler.setOptions(options);
                wsHandler.setHandler(cometdHandler);
            }
        }
    }

    protected void prepareClient(Transport wsType) {
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
        cometdURL = "http://localhost:" + port + cometdPath;
        bayeuxServer = (BayeuxServer)context.getContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    protected void startClient() throws Exception {
        httpClient.start();
    }

    protected BayeuxClient newBayeuxClient(Transport wsType) {
        return newBayeuxClient(wsType, cometdURL);
    }

    protected BayeuxClient newBayeuxClient(Transport wsType, String url) {
        return new BayeuxClient(url, newWebSocketTransport(wsType, null));
    }

    protected ClientTransport newLongPollingTransport(Map<String, Object> options) {
        return new JettyHttpClientTransport(options, httpClient);
    }

    protected ClientTransport newWebSocketTransport(Transport wsType, Map<String, Object> options) {
        return newWebSocketTransport(wsType, null, options);
    }

    protected ClientTransport newWebSocketTransport(Transport wsType, String url, Map<String, Object> options) {
        return switch (wsType) {
            case WEBSOCKET_JAKARTA -> newWebSocketTransport(url, options, wsClientContainer);
            case WEBSOCKET_JETTY -> newJettyWebSocketTransport(url, options, wsClient);
            case WEBSOCKET_OKHTTP -> newOkHttpWebSocketTransport(url, options, okHttpClient);
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

    public enum Transport {
        WEBSOCKET_JAKARTA, WEBSOCKET_JETTY, WEBSOCKET_OKHTTP
    }
}
