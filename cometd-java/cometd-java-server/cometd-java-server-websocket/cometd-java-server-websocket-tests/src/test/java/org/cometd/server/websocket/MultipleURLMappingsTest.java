/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.server.websocket;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MultipleURLMappingsTest {
    private static final String JSR_WS_TRANSPORT = "org.cometd.server.websocket.javax.WebSocketTransport";
    private static final String JETTY_WS_TRANSPORT = "org.cometd.server.websocket.jetty.JettyWebSocketTransport";

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {JSR_WS_TRANSPORT},
                        {JETTY_WS_TRANSPORT}
                }
        );
    }

    private final String wsTransportClass;
    private Server server;
    private ServerConnector connector;
    private ServletContextHandler context;
    private WebSocketContainer wsClientContainer;
    private WebSocketClient wsClient;
    private String servletPath1 = "/cometd1";
    private String servletPath2 = "/cometd2";

    public MultipleURLMappingsTest(String wsTransportClass) {
        this.wsTransportClass = wsTransportClass;
    }

    @Before
    public void prepare() throws Exception {
        server = new Server();

        connector = new ServerConnector(server);
        server.addConnector(connector);

        context = new ServletContextHandler(server, "/", true, false);

        ServletContainerInitializer initializer;
        if (JSR_WS_TRANSPORT.equals(wsTransportClass)) {
            initializer = new JavaxWebSocketServletContainerInitializer();
        } else if (JETTY_WS_TRANSPORT.equals(wsTransportClass)) {
            initializer = new JettyWebSocketServletContainerInitializer();
        } else {
            throw new IllegalArgumentException("Unsupported transport " + wsTransportClass);
        }

        context.addEventListener(new ServletContextListener() {
            @Override
            public void contextInitialized(ServletContextEvent sce) {
                try {
                    initializer.onStartup(Set.of(), sce.getServletContext());
                } catch (ServletException x) {
                    throw new RuntimeException(x);
                }
            }
        });

        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("transports", wsTransportClass);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", servletPath1 + "," + servletPath2);
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, servletPath1 + "/*");
        context.addServlet(cometdServletHolder, servletPath2 + "/*");

        switch (wsTransportClass) {
            case JSR_WS_TRANSPORT:
                wsClientContainer = ContainerProvider.getWebSocketContainer();
                server.addBean(wsClientContainer);
                break;
            case JETTY_WS_TRANSPORT:
                wsClient = new WebSocketClient();
                server.addBean(wsClient);
                break;
            default:
                throw new AssertionError();
        }

        server.start();
    }

    private BayeuxClient newBayeuxClient(String servletPath) {
        return new BayeuxClient("http://localhost:" + connector.getLocalPort() + servletPath, newWebSocketTransport());
    }

    private ClientTransport newWebSocketTransport() {
        switch (wsTransportClass) {
            case JSR_WS_TRANSPORT:
                return new org.cometd.client.websocket.javax.WebSocketTransport(null, null, wsClientContainer);
            case JETTY_WS_TRANSPORT:
                return new org.cometd.client.websocket.jetty.JettyWebSocketTransport(null, null, wsClient);
            default:
                throw new AssertionError();
        }
    }

    @After
    public void dispose() throws Exception {
        server.stop();
    }

    @Test
    public void testMultipleURLMappings() throws Exception {
        BayeuxClient client1 = newBayeuxClient(servletPath1);
        client1.handshake();
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client2 = newBayeuxClient(servletPath2);
        client2.handshake();
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/foobarbaz";
        final CountDownLatch messageLatch = new CountDownLatch(4);
        final CountDownLatch subscribeLatch = new CountDownLatch(2);
        client1.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        client2.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        client1.getChannel(channelName).publish("1");
        client2.getChannel(channelName).publish("2");

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        client1.disconnect(1000);
        client2.disconnect(1000);
    }
}
