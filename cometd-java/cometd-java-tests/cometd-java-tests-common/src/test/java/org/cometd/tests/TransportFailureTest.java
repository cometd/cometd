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
package org.cometd.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TransportFailureTest {
    private Server server;
    private ServerConnector connector;
    private String cometdURL;
    private BayeuxServerImpl bayeux;
    private HttpClient httpClient;
    private WebSocketContainer wsClient;
    private String cometdServletPath;

    private void startServer() throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        WebSocketServerContainerInitializer.configure(context, null);

        cometdServletPath = "/cometd";

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdServletPath);
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        httpClient = new HttpClient();
        server.addBean(httpClient);

        wsClient = ContainerProvider.getWebSocketContainer();
        server.addBean(wsClient);

        server.start();
        cometdURL = "http://localhost:" + connector.getLocalPort() + cometdServletPath;
        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    @AfterEach
    public void dispose() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testTransportNegotiationClientWebSocketAndLongPollingServerLongPolling() throws Exception {
        startServer();
        bayeux.setAllowedTransports("long-polling");

        ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        ClientTransport longPollingTransport = new JettyHttpClientTransport(null, httpClient);
        CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assertions.assertEquals(webSocketTransport.getName(), oldTransportName);
                Assertions.assertEquals(longPollingTransport.getName(), newTransportName);
                failureLatch.countDown();
            }
        };

        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                successLatch.countDown();
            } else {
                failedLatch.countDown();
            }
        });

        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    @Test
    public void testTransportNegotiationFailureForClientWebSocketServerLongPolling() throws Exception {
        startServer();
        bayeux.setAllowedTransports("long-polling");

        ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assertions.assertEquals(webSocketTransport.getName(), oldTransportName);
                Assertions.assertNull(newTransportName);
                failureLatch.countDown();
            }
        };

        CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (!message.isSuccessful()) {
                failedLatch.countDown();
            }
        });

        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testTransportNegotiationFailureForClientLongPollingServerCallbackPolling() throws Exception {
        startServer();
        // Only callback-polling on server (via extension), only long-polling on client.
        bayeux.setAllowedTransports("long-polling", "callback-polling");
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new String[]{"callback-polling"});
                }
                return true;
            }
        });

        CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                failureLatch.countDown();
            }
        };
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testTransportNegotiationFailureForClientLongPollingServerWebSocket() throws Exception {
        startServer();
        bayeux.setAllowedTransports("websocket");

        ClientTransport longPollingTransport = new JettyHttpClientTransport(null, httpClient);
        CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, longPollingTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assertions.assertEquals(longPollingTransport.getName(), oldTransportName);
                Assertions.assertEquals(longPollingTransport.getName(), newTransportName);
                failureLatch.countDown();
            }
        };

        CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (!message.isSuccessful()) {
                failedLatch.countDown();
            }
        });

        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    @Test
    public void testChangeTransportURLOnMetaConnectFailure() throws Exception {
        startServer();
        ServerConnector connector2 = new ServerConnector(server);
        server.addConnector(connector2);
        connector2.start();

        bayeux.addExtension(new MetaConnectFailureExtension() {
            @Override
            protected boolean onMetaConnect(int count) throws Exception {
                if (count != 2) {
                    return true;
                }
                connector.stop();
                return false;
            }
        });

        String newURL = "http://localhost:" + connector2.getLocalPort() + cometdServletPath;

        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            private int metaConnects;

            @Override
            protected void onTransportFailure(Message message, ClientTransport.FailureInfo failureInfo, ClientTransport.FailureHandler handler) {
                ++metaConnects;
                if (metaConnects == 1 && Channel.META_CONNECT.equals(message.getChannel())) {
                    ClientTransport transport = getTransport();
                    transport.setURL(newURL);
                    failureInfo.transport = transport;
                    handler.handle(failureInfo);
                } else {
                    super.onTransportFailure(message, failureInfo, handler);
                }
            }
        };

        // The second connect fails, the third connect should succeed on the new URL.
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private int metaConnects;

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                ++metaConnects;
                if (metaConnects == 3 && message.isSuccessful()) {
                    if (client.getTransport().getURL().equals(newURL)) {
                        latch.countDown();
                    }
                }
            }
        });

        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
        connector2.stop();
    }

    @Test
    public void testChangeTransportOnMetaConnectFailure() throws Exception {
        startServer();

        bayeux.addExtension(new MetaConnectFailureExtension() {
            @Override
            protected boolean onMetaConnect(int count) {
                return count != 2;
            }
        });

        ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        ClientTransport longPollingTransport = new JettyHttpClientTransport(null, httpClient);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
            private int metaConnects;

            @Override
            protected void onTransportFailure(Message message, ClientTransport.FailureInfo failureInfo, ClientTransport.FailureHandler handler) {
                ++metaConnects;
                if (metaConnects == 1 && Channel.META_CONNECT.equals(message.getChannel())) {
                    failureInfo.transport = webSocketTransport;
                    handler.handle(failureInfo);
                } else {
                    super.onTransportFailure(message, failureInfo, handler);
                }
            }
        };

        // The second connect fails, the third connect should succeed on the new transport.
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private int metaConnects;

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                ++metaConnects;
                if (metaConnects == 3 && message.isSuccessful()) {
                    if (client.getTransport().getName().equals(webSocketTransport.getName())) {
                        latch.countDown();
                    }
                }
            }
        });

        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    private abstract static class MetaConnectFailureExtension implements BayeuxServer.Extension {
        private final AtomicInteger metaConnects = new AtomicInteger();

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                try {
                    return onMetaConnect(metaConnects.incrementAndGet());
                } catch (Exception x) {
                    throw new RuntimeException(x);
                }
            }
            return true;
        }

        protected abstract boolean onMetaConnect(int count) throws Exception;
    }
}
