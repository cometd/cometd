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
package org.cometd.tests.spring.websocket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import okhttp3.OkHttpClient;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.http.AsyncJSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

public class SpringFrameworkWebSocketConfigurationTest {
    private Server server;
    private ServletContextHandler context;

    private String startServer(Class<?> wsTransportClass, String springConfig) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        String contextPath = "/ctx";
        context = new ServletContextHandler(server, contextPath, true, false);

        if (WebSocketTransport.class.equals(wsTransportClass)) {
            WebSocketServerContainerInitializer.configure(context, null);
        } else if (JettyWebSocketTransport.class.equals(wsTransportClass)) {
            NativeWebSocketServletContainerInitializer.configure(context, null);
            WebSocketUpgradeFilter.configure(context);
        } else {
            throw new IllegalArgumentException();
        }

        context.addEventListener(new ContextLoaderListener());
        context.getInitParams().put(ContextLoader.CONFIG_LOCATION_PARAM, "classpath:" + springConfig);

        // CometD servlet
        String cometdServletPath = "/cometd";
        String cometdURLMapping = cometdServletPath + "/*";

        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        String transports = wsTransportClass + "," + AsyncJSONTransport.class.getName();
        cometdServletHolder.setInitParameter("transports", transports);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        server.start();

        return "http://localhost:" + connector.getLocalPort() + contextPath + cometdServletPath;
    }

    @AfterEach
    public void dispose() {
        LifeCycle.stop(server);
    }

    @Test
    public void testXMLSpringConfigurationWithJSRWebSocket() throws Exception {
        String url = startServer(WebSocketTransport.class, "applicationContext-javax-websocket.xml");
        WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
        ClientTransport clientTransport = new org.cometd.client.websocket.javax.WebSocketTransport(null, null, webSocketContainer);
        testXMLSpringConfigurationWithWebSocket(url, clientTransport);
        LifeCycle.stop(webSocketContainer);
    }

    @Test
    public void testXMLSpringConfigurationWithOkHttpWebSocket() throws Exception {
        String url = startServer(WebSocketTransport.class, "applicationContext-javax-websocket.xml");
        OkHttpClient okHttp = new OkHttpClient();
        ClientTransport clientTransport = new OkHttpWebSocketTransport(null, okHttp);
        testXMLSpringConfigurationWithWebSocket(url, clientTransport);
    }

    @Test
    public void testXMLSpringConfigurationWithJettyWebSocket() throws Exception {
        String url = startServer(JettyWebSocketTransport.class, "applicationContext-jetty-websocket.xml");
        WebSocketClient wsClient = new WebSocketClient();
        LifeCycle.start(wsClient);
        ClientTransport clientTransport = new org.cometd.client.websocket.jetty.JettyWebSocketTransport(null, null, wsClient);
        testXMLSpringConfigurationWithWebSocket(url, clientTransport);
        LifeCycle.stop(wsClient);
    }

    private void testXMLSpringConfigurationWithWebSocket(String url, ClientTransport clientTransport) throws Exception {
        WebApplicationContext applicationContext = WebApplicationContextUtils.getRequiredWebApplicationContext(context.getServletContext());
        Assertions.assertNotNull(applicationContext);

        BayeuxServerImpl bayeuxServer = (BayeuxServerImpl)applicationContext.getBean("bayeux");
        Assertions.assertNotNull(bayeuxServer);
        Assertions.assertTrue(bayeuxServer.isStarted());

        BayeuxServerImpl bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        Assertions.assertSame(bayeuxServer, bayeux);

        ServerTransport transport = bayeuxServer.getTransport("websocket");
        Assertions.assertNotNull(transport);

        BayeuxClient client = new BayeuxClient(url, clientTransport);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for connect to establish
        Thread.sleep(1000);

        Assertions.assertEquals("websocket", client.getTransport().getName());

        CountDownLatch latch = new CountDownLatch(1);
        client.disconnect(dcReply -> latch.countDown());
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
