/*
 * Copyright (c) 2008 the original author or authors.
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
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.http.jakarta.CometDServlet;
import org.cometd.server.http.jetty.CometDHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ManyBayeuxServersTest {
    public static Transport[] transports() {
        return Transport.values();
    }

    @RegisterExtension
    public final BeforeTestExecutionCallback printMethodName = context ->
            System.err.printf("Running %s.%s() %s%n", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName(), context.getDisplayName());
    private Server server;
    private ServerConnector connector;
    private Context context;

    public void startServer(Transport transport, Map<String, String> options1, Map<String, String> options2) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        switch (transport) {
            case JAKARTA -> {
                ServletContextHandler servletContext = new ServletContextHandler("/");
                server.setHandler(servletContext);
                context = servletContext.getContext();

                ServletHolder servletHolder1 = new ServletHolder(new CometDServlet());
                servletHolder1.setInitParameters(options1);
                servletContext.addServlet(servletHolder1, "/cometd1/*");

                ServletHolder servletHolder2 = new ServletHolder(new CometDServlet());
                servletHolder2.setInitParameters(options2);
                servletContext.addServlet(servletHolder2, "/cometd2/*");
            }
            case JETTY -> {
                PathMappingsHandler pathsHandler = new PathMappingsHandler();
                server.setHandler(pathsHandler);
                context = server.getContext();

                CometDHandler handler1 = new CometDHandler();
                handler1.setOptions(options1);
                pathsHandler.addMapping(PathSpec.from("/cometd1/*"), handler1);

                CometDHandler handler2 = new CometDHandler();
                handler2.setOptions(options2);
                pathsHandler.addMapping(PathSpec.from("/cometd2/*"), handler2);
            }
        }

        server.start();
    }


    @AfterEach
    public void stopServer() {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testManyBayeuxServers(Transport transport) throws Exception {
        Map<String, String> options1 = new HashMap<>();
        String contextAttributeName1 = "org.cometd.bayeux.server.1";
        options1.put(BayeuxServerImpl.CONTEXT_ATTRIBUTE_NAME_OPTION, contextAttributeName1);

        Map<String, String> options2 = new HashMap<>();
        String contextAttributeName2 = "org.cometd.bayeux.server.2";
        options2.put(BayeuxServerImpl.CONTEXT_ATTRIBUTE_NAME_OPTION, contextAttributeName2);

        startServer(transport, options1, options2);

        assertNull(context.getAttribute(BayeuxServer.ATTRIBUTE));
        BayeuxServer bayeuxServer1 = (BayeuxServer)context.getAttribute(contextAttributeName1);
        assertNotNull(bayeuxServer1);
        BayeuxServer bayeuxServer2 = (BayeuxServer)context.getAttribute(contextAttributeName2);
        assertNotNull(bayeuxServer2);
        assertNotSame(bayeuxServer1, bayeuxServer2);
    }
}
