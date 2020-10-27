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
package org.cometd.tests.spring;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class SpringFrameworkConfigurationTest {
    private Server server;
    private ServletContextHandler context;
    private CometDServlet cometdServlet;
    private HttpClient httpClient;

    private String startServer(Consumer<ServletContextHandler> config) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        String contextPath = "/cometd";
        context = new ServletContextHandler(server, contextPath, ServletContextHandler.SESSIONS);

        // Setup CometDServlet.
        cometdServlet = new CometDServlet();
        ServletHolder cometdServletHolder = new ServletHolder(cometdServlet);
        String cometdServletPath = "/cometd";
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        config.accept(context);

        httpClient = new HttpClient();
        server.addManaged(httpClient);

        server.start();

        return "http://localhost:" + connector.getLocalPort() + contextPath + cometdServletPath;
    }

    @AfterEach
    public void dispose() {
        LifeCycle.stop(server);
    }

    @Test
    public void testXMLSpringConfiguration() throws Exception {
        String url = startServer(context -> {
            // Add Spring listener
            context.addEventListener(new ContextLoaderListener());
            context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:/applicationContext-server.xml");
        });

        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
        Assertions.assertNotNull(applicationContext);

        int sweepPeriod = (Integer)applicationContext.getBean("sweepPeriod");

        BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
        Assertions.assertNotNull(bayeuxServer);
        Assertions.assertTrue(bayeuxServer.isStarted());
        Assertions.assertEquals(sweepPeriod, bayeuxServer.getOption("sweepPeriod"));

        Assertions.assertSame(bayeuxServer, cometdServlet.getBayeux());

        CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient bayeuxClient = new BayeuxClient(url, new JettyHttpClientTransport(null, httpClient));
        bayeuxClient.handshake(hsReply -> {
            if (hsReply.isSuccessful()) {
                bayeuxClient.disconnect(dcReply -> latch.countDown());
            }
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
