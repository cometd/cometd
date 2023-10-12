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
package org.cometd.server.http;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.http.jakarta.CometDServlet;
import org.cometd.server.http.jetty.CometDHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

public abstract class AbstractBayeuxServerTest {
    public static Transport[] transports() {
        return Transport.values();
    }

    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected Server server;
    protected ServerConnector connector;
    protected int port;
    protected ContextHandler context;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;
    protected long timeout = 2000;

    public void startServer(Transport transport, Map<String, String> options) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        String contextPath = "/";
        String cometdPath = "/cometd";
        Supplier<BayeuxServer> getBayeuxServer = switch (transport) {
            case JAKARTA -> {
                ServletContextHandler servletContext = new ServletContextHandler(contextPath);
                context = servletContext;
                server.setHandler(context);
                CometDServlet cometdServlet = new CometDServlet();
                ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
                if (options == null) {
                    options = new HashMap<>();
                }
                options.put("timeout", String.valueOf(timeout));
                options.put("transports", JSONHttpTransport.class.getName());
                for (Map.Entry<String, String> entry : options.entrySet()) {
                    cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
                }
                servletContext.addServlet(cometdServletHolder, cometdPath + "/*");
                yield cometdServlet::getBayeuxServer;
            }
            case JETTY -> {
                context = new ContextHandler(contextPath);
                server.setHandler(context);
                CometDHandler cometdHandler = new CometDHandler();
                context.setHandler(cometdHandler);
                if (options == null) {
                    options = new HashMap<>();
                }
                options.put("timeout", String.valueOf(timeout));
                options.put("transports", JSONHttpTransport.class.getName());
                cometdHandler.setOptions(options);
                yield cometdHandler::getBayeuxServer;
            }
        };

        server.start();
        port = connector.getLocalPort();

        cometdURL = "http://localhost:" + port + cometdPath;

        bayeux = (BayeuxServerImpl)getBayeuxServer.get();
    }

    @AfterEach
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    public enum Transport {
        JAKARTA, JETTY
    }
}
