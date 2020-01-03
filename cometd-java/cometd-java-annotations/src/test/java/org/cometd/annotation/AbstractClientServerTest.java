/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.annotation;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;

public abstract class AbstractClientServerTest {
    protected Server server;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;
    protected HttpClient httpClient;

    @Before
    public void prepare() throws Exception {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitOrder(1);

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + cometdServletPath;

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    protected BayeuxClient newBayeuxClient() {
        return new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
    }

    protected void disconnectBayeuxClient(BayeuxClient client) {
        client.disconnect(1000);
    }
}
