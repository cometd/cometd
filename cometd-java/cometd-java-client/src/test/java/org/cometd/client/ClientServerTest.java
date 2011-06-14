/*
 * Copyright (c) 2010 the original author or authors.
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
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;

public class ClientServerTest
{
    protected Connector connector;
    protected Server server;
    protected HttpClient httpClient;
    protected String cometdURL;
    protected BayeuxServer bayeux;
    protected BayeuxClient client;

    public void startServer(Map<String, String> initParams) throws Exception
    {
        server = new Server();

        connector = new SelectChannelConnector();
        connector.setMaxIdleTime(30000);
        server.addConnector(connector);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometdServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("logLevel", "3");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null)
        {
            for (Map.Entry<String, String> entry : initParams.entrySet())
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }

        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + contextPath + cometdServletPath;

        bayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        httpClient = new HttpClient();
        httpClient.start();

        client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
    }

    @After
    public void stopServer() throws Exception
    {
        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);

        httpClient.stop();

        server.stop();
        server.join();
    }
}
