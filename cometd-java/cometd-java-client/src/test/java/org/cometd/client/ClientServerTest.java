/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.client;

import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public abstract class ClientServerTest {
    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    protected ServerConnector connector;
    protected Server server;
    protected ServletContextHandler context;
    protected HttpClient httpClient;
    protected String cometdServletPath;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;

    public void start(Map<String, String> initParams) throws Exception {
        startServer(initParams);
        startClient();
    }

    protected void startServer(Map<String, String> initParams, ConnectionFactory... connectionFactories) throws Exception {
        server = new Server();

        connector = new ServerConnector(server, 1, 1, connectionFactories);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);

        context = new ServletContextHandler(server, "/");

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }

        cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + cometdServletPath;

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    protected void startClient() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
    }

    protected BayeuxClient newBayeuxClient() {
        return new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
    }

    protected void disconnectBayeuxClient(BayeuxClient client) {
        client.disconnect(1000);
    }

    @After
    public void stopServer() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }

        if (server != null) {
            server.stop();
            server.join();
        }
    }
}
