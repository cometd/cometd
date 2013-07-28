/*
 * Copyright (c) 2011 the original author or authors.
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

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.websocket.server.JettyWebSocketTransport;
import org.cometd.websocket.server.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.WebSocketConfiguration;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class ClientServerWebSocketTest
{
    protected static final String WEBSOCKET_JSR_356 = "JSR 356";
    protected static final String WEBSOCKET_JETTY = "JETTY";

    @Parameterized.Parameters(name = "{index}: WebSocket implementation: {0}")
     public static Iterable<Object[]> data()
     {
         return Arrays.asList(new Object[][]
                 {
                         {WEBSOCKET_JSR_356},
                         {WEBSOCKET_JETTY}
                 }
         );
     }

    @Rule
    public final TestWatcher testName = new TestWatcher()
    {
        @Override
        protected void starting(Description description)
        {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    protected final String implementation;
    protected ServerConnector connector;
    protected Server server;
    protected String contextPath;
    protected ServletContextHandler context;
    protected String cometdServletPath;
    protected HttpClient httpClient;
    protected QueuedThreadPool wsThreadPool;
    protected WebSocketClient wsClient;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;
    protected ServerContainer webSocketContainer;

    protected ClientServerWebSocketTest(String implementation)
    {
        this.implementation = implementation;
    }

    public void runServer(Map<String, String> initParams) throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server);
        //connector.setMaxIdleTime(30000);
        server.addConnector(connector);

        contextPath = "";
        context = new ServletContextHandler(server, contextPath, true, false);

        // WebSocket Filter
        webSocketContainer = WebSocketConfiguration.configureContext(context);
        webSocketContainer.start();

        // CometD servlet
        cometdServletPath = "/cometd";
//        String cometdURLMapping = cometdServletPath + "/*";
        String cometdURLMapping = cometdServletPath;
                ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        String serverTransport = WEBSOCKET_JSR_356.equals(implementation) ?
                WebSocketTransport.class.getName() : JettyWebSocketTransport.class.getName();
        cometdServletHolder.setInitParameter("transports", serverTransport);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (debugTests())
            cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null)
        {
            for (Map.Entry<String, String> entry : initParams.entrySet())
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }
        context.addServlet(cometdServletHolder, cometdURLMapping);

        httpClient = new HttpClient();

        if (WEBSOCKET_JETTY.equals(implementation))
        {
            wsThreadPool = new QueuedThreadPool();
            wsThreadPool.setName(wsThreadPool.getName() + "-client");
            wsClient = new WebSocketClient();
            wsClient.setExecutor(wsThreadPool);
        }

        startServer();
    }

    protected void startServer() throws Exception
    {
        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + contextPath + cometdServletPath;

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient.start();

        if (WEBSOCKET_JETTY.equals(implementation))
            wsClient.start();
    }

    protected BayeuxClient newBayeuxClient()
    {
        BayeuxClient client = new BayeuxClient(cometdURL, newWebSocketTransport(null));
        client.setDebugEnabled(debugTests());
        return client;
    }

    protected ClientTransport newLongPollingTransport(Map<String, Object> options)
    {
        LongPollingTransport transport = new LongPollingTransport(options, httpClient);
        transport.setDebugEnabled(debugTests());
        return transport;
    }

    protected ClientTransport newWebSocketTransport(Map<String, Object> options)
    {
        ClientTransport result;
        switch (implementation)
        {
            case WEBSOCKET_JSR_356:
                result = new org.cometd.websocket.client.WebSocketTransport.Factory().newClientTransport(options);
                break;
            case WEBSOCKET_JETTY:
                result = new org.cometd.websocket.client.JettyWebSocketTransport.Factory(wsClient).newClientTransport(options);
                break;
            default:
                throw new IllegalArgumentException();
        }
        result.setDebugEnabled(debugTests());
        return result;
    }

    protected void disconnectBayeuxClient(BayeuxClient client)
    {
        client.disconnect(1000);
    }

    @After
    public void stopServer() throws Exception
    {
        if (WEBSOCKET_JETTY.equals(implementation))
            wsClient.stop();

        httpClient.stop();

        webSocketContainer.stop();

        server.stop();
        server.join();
    }

    protected boolean debugTests()
    {
        return Boolean.getBoolean("debugTests");
    }
}
