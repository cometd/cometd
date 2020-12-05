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
package org.cometd.server.websocket;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MultipleURLMappingsTest {
    private static final String JSR_WS_TRANSPORT = "org.cometd.server.websocket.javax.WebSocketTransport";
    private static final String JETTY_WS_TRANSPORT = "org.cometd.server.websocket.jetty.JettyWebSocketTransport";

    public static List<String> wsTransports() {
        return Arrays.asList(JSR_WS_TRANSPORT, JETTY_WS_TRANSPORT);
    }

    private Server server;
    private ServerConnector connector;
    private WebSocketContainer wsClientContainer;
    private WebSocketClient wsClient;
    private String servletPath1 = "/cometd1";
    private String servletPath2 = "/cometd2";

    private void start(String wsTransportClass) throws Exception {
        server = new Server();

        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);

        switch (wsTransportClass) {
            case JSR_WS_TRANSPORT:
                JakartaWebSocketServletContainerInitializer.configure(context, null);
                break;
            case JETTY_WS_TRANSPORT:
                JettyWebSocketServletContainerInitializer.configure(context, null);
                break;
            default:
                throw new IllegalArgumentException("Unsupported transport " + wsTransportClass);
        }

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

    private BayeuxClient newBayeuxClient(String wsTransportClass, String servletPath) {
        return new BayeuxClient("http://localhost:" + connector.getLocalPort() + servletPath, newWebSocketTransport(wsTransportClass));
    }

    private ClientTransport newWebSocketTransport(String wsTransportClass) {
        switch (wsTransportClass) {
            case JSR_WS_TRANSPORT:
                return new org.cometd.client.websocket.javax.WebSocketTransport(null, null, wsClientContainer);
            case JETTY_WS_TRANSPORT:
                return new org.cometd.client.websocket.jetty.JettyWebSocketTransport(null, null, wsClient);
            default:
                throw new AssertionError();
        }
    }

    @AfterEach
    public void dispose() throws Exception {
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("wsTransports")
    public void testMultipleURLMappings(String wsTransportClass) throws Exception {
        start(wsTransportClass);

        BayeuxClient client1 = newBayeuxClient(wsTransportClass, servletPath1);
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client2 = newBayeuxClient(wsTransportClass, servletPath2);
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/foobarbaz";
        CountDownLatch messageLatch = new CountDownLatch(4);
        CountDownLatch subscribeLatch = new CountDownLatch(2);
        client1.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        client2.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        client1.getChannel(channelName).publish("1");
        client2.getChannel(channelName).publish("2");

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        client1.disconnect(1000);
        client2.disconnect(1000);
    }
}
