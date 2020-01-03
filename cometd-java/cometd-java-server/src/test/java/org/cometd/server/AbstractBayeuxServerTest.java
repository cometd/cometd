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
package org.cometd.server;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.cometd.server.transport.AsyncJSONTransport;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractBayeuxServerTest {
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data() {
        Object[][] data = {{JSONTransport.class.getName()}, {AsyncJSONTransport.class.getName()}};
        return Arrays.asList(data);
    }

    @Rule
    public final TestWatcher testName = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };

    protected final String serverTransport;
    protected Server server;
    protected ServerConnector connector;
    protected int port;
    protected ServletContextHandler context;
    protected CometDServlet cometdServlet;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;
    protected long timeout = 2000;

    protected AbstractBayeuxServerTest(String serverTransport) {
        this.serverTransport = serverTransport;
    }

    public void startServer(Map<String, String> options) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup comet servlet
        cometdServlet = new CometDServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        if (options == null) {
            options = new HashMap<>();
        }
        options.put("timeout", String.valueOf(timeout));
        options.put("transports", serverTransport);
        for (Map.Entry<String, String> entry : options.entrySet()) {
            cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
        }
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        server.start();
        port = connector.getLocalPort();

        String contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;

        bayeux = cometdServlet.getBayeux();
    }

    @After
    public void stopServer() throws Exception {
        server.stop();
        server.join();
    }
}
