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
package org.cometd.server.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.handler.transport.AsyncJSONTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

public abstract class AbstractBayeuxServerTest
{
    public static List<String> transports() {
        return List.of(AsyncJSONTransport.class.getName());
    }

    @RegisterExtension
    final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    protected Server server;
    protected ServerConnector connector;
    protected int port;
    protected CometDHandler cometDHandler;
    protected String cometdURL;
    protected BayeuxServerImpl bayeux;
    protected long timeout = 2000;

    public void startServer(String serverTransport, Map<String, String> options) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ContextHandler contextHandler = new ContextHandler(contextPath);
        handlers.addHandler(contextHandler);

        // Setup comet handler
        cometDHandler = new CometDHandler();
        contextHandler.setHandler(cometDHandler);

        if (options == null) {
            options = new HashMap<>();
        }
        options.put("timeout", String.valueOf(timeout));
        options.put("transports", serverTransport);
        String cometdServletPath = "/cometd";

        server.start();
        port = connector.getLocalPort();

        String contextURL = "http://localhost:" + port + contextPath;
        cometdURL = contextURL + cometdServletPath;

        bayeux = cometDHandler.getBayeux();
    }

    @AfterEach
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }
}
