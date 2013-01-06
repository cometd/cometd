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

import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

public abstract class ClientServerWebSocketTest
{
    @Rule
    public final TestWatchman testName = new TestWatchman()
    {
        @Override
        public void starting(FrameworkMethod method)
        {
            super.starting(method);
            System.err.printf("Running %s.%s%n", method.getMethod().getDeclaringClass().getName(), method.getName());
        }
    };
    protected ServerConnector connector;
    protected Server server;
    protected String contextPath;
    protected ServletContextHandler context;
    protected String cometdServletPath;
    protected HttpClient httpClient;
    protected QueuedThreadPool wsThreadPool;
    protected WebSocketClientFactory wsFactory;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;

    public void runServer(Map<String, String> initParams) throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server);
        //connector.setMaxIdleTime(30000);
        server.addConnector(connector);

        contextPath = "";
        context = new ServletContextHandler(server, contextPath, true, false);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("transports", org.cometd.websocket.server.WebSocketTransport.class.getName());
        cometdServletHolder.setInitParameter("timeout", "10000");
        if (debugTests())
            cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null)
        {
            for (Map.Entry<String, String> entry : initParams.entrySet())
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }

        cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        httpClient = new HttpClient();

        wsThreadPool = new QueuedThreadPool();
        wsThreadPool.setName(wsThreadPool.getName() + "-client");
        //wsThreadPool.setMaxStopTimeMs(1000);

        wsFactory = new WebSocketClientFactory(wsThreadPool);

        startServer();
    }

    protected void startServer() throws Exception
    {
        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + contextPath + cometdServletPath;

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient.start();

        wsThreadPool.start();
        wsFactory.start();
    }

    protected BayeuxClient newBayeuxClient()
    {
        WebSocketTransport transport = WebSocketTransport.create(null, wsFactory);
        transport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.setDebugEnabled(debugTests());
        return client;
    }

    protected void disconnectBayeuxClient(BayeuxClient client)
    {
        client.disconnect(1000);
    }

    @After
    public void stopServer() throws Exception
    {
        wsFactory.stop();
        wsThreadPool.stop();

        httpClient.stop();

        server.stop();
        server.join();
    }

    protected boolean debugTests()
    {
        return Boolean.getBoolean("debugTests");
    }
}
