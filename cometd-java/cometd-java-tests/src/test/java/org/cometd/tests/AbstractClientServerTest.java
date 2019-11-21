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
package org.cometd.tests;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.http.okhttp.OkHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.http.AsyncJSONTransport;
import org.cometd.server.http.JSONTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
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
public abstract class AbstractClientServerTest {
    @Parameterized.Parameters(name = "{0}")
    public static Object[] data() {
        return Transport.values();
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
    protected final Transport transport;
    protected Server server;
    protected ServerConnector connector;
    protected ServletContextHandler context;
    protected String cometdServletPath;
    protected String cometdURL;
    protected BayeuxServer bayeux;
    private ScheduledExecutorService scheduler;
    private HttpClient httpClient;
    private WebSocketContainer wsContainer;
    private WebSocketClient wsClient;
    private OkHttpClient okHttpClient;

    protected AbstractClientServerTest(Transport transport) {
        this.transport = transport;
        this.cometdServletPath = "/cometd";
    }

    public void startServer(Map<String, String> initParams) throws Exception {
        server = new Server();

        connector = new ServerConnector(server, 1, 1);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);

        context = new ServletContextHandler(server, "/");

        switch (transport) {
            case JAVAX_WEBSOCKET:
            case OKHTTP_WEBSOCKET:
                WebSocketServerContainerInitializer.configureContext(context);
                break;
            case JETTY_WEBSOCKET:
                WebSocketUpgradeFilter.configureContext(context);
                break;
            default:
                break;
        }

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }

        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + cometdServletPath;

        bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        startClient();
    }

    protected void startClient() throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        switch (transport) {
            case JETTY_HTTP:
            case ASYNC_HTTP:
                httpClient = new HttpClient();
                httpClient.start();
                break;
            case JAVAX_WEBSOCKET:
                wsContainer = ContainerProvider.getWebSocketContainer();
                break;
            case JETTY_WEBSOCKET:
                httpClient = new HttpClient();
                httpClient.start();
                wsClient = new WebSocketClient(httpClient);
                wsClient.start();
                break;
            case OKHTTP_HTTP:
            case OKHTTP_WEBSOCKET:
                // There's no lifecycle of OkHttp client.
                okHttpClient = new OkHttpClient();
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    protected Map<String, String> serverOptions() {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.TRANSPORTS_OPTION, serverTransport());
        options.put("ws.cometdURLMapping", cometdServletPath + "/*");
        return options;
    }

    protected String serverTransport() {
        switch (transport) {
            case JETTY_HTTP:
                return JSONTransport.class.getName();
            case ASYNC_HTTP:
            case OKHTTP_HTTP:
                return AsyncJSONTransport.class.getName();
            case JAVAX_WEBSOCKET:
            case OKHTTP_WEBSOCKET:
                return org.cometd.server.websocket.javax.WebSocketTransport.class.getName();
            case JETTY_WEBSOCKET:
                return org.cometd.server.websocket.jetty.JettyWebSocketTransport.class.getName();
            default:
                throw new IllegalArgumentException();
        }
    }

    protected BayeuxClient newBayeuxClient() {
        return new BayeuxClient(cometdURL, newClientTransport(null));
    }

    protected ClientTransport newClientTransport(Map<String, Object> options) {
        switch (transport) {
            case JETTY_HTTP:
            case ASYNC_HTTP:
                return new JettyHttpClientTransport(options, httpClient);
            case OKHTTP_HTTP:
                return new OkHttpClientTransport(options, okHttpClient);
            case JAVAX_WEBSOCKET:
                return new WebSocketTransport(options, scheduler, wsContainer);
            case JETTY_WEBSOCKET:
                return new JettyWebSocketTransport(options, scheduler, wsClient);
            case OKHTTP_WEBSOCKET:
                return new OkHttpWebSocketTransport(options, okHttpClient);
            default:
                throw new IllegalArgumentException();
        }
    }

    protected void disconnectBayeuxClient(BayeuxClient client) {
        client.disconnect(1000);
    }

    @After
    public void dispose() throws Exception {
        stopClient();
        if (server != null) {
            server.stop();
        }
    }

    private void stopClient() throws Exception {
        if (wsClient != null) {
            wsClient.stop();
        }
        if (wsContainer instanceof LifeCycle) {
            ((LifeCycle)wsContainer).stop();
        }
        if (httpClient != null) {
            httpClient.stop();
        }
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

    public enum Transport {
        JETTY_HTTP, ASYNC_HTTP, OKHTTP_HTTP, JAVAX_WEBSOCKET, JETTY_WEBSOCKET, OKHTTP_WEBSOCKET
    }
}
