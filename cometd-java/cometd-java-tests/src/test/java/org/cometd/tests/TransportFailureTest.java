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

import java.util.Map;
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
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class TransportFailureTest {
    private Server server;
    private ServerConnector connector;
    private String cometdURL;
    private BayeuxServerImpl bayeux;
    private HttpClient httpClient;
    private WebSocketContainer wsClient;
    private String cometdServletPath;

    private void startServer(Map<String, String> initParams) throws Exception {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        WebSocketServerContainerInitializer.configureContext(context);

        cometdServletPath = "/cometd";

        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdServletPath);
        cometdServletHolder.setInitOrder(1);
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");

        httpClient = new HttpClient();
        server.addBean(httpClient);

        wsClient = ContainerProvider.getWebSocketContainer();
        server.addBean(wsClient);

        server.start();
        cometdURL = "http://localhost:" + connector.getLocalPort() + cometdServletPath;
        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    @After
    public void dispose() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testTransportNegotiationClientWebSocketAndLongPollingServerLongPolling() throws Exception {
        startServer(null);
        bayeux.setAllowedTransports("long-polling");

        final ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        final ClientTransport longPollingTransport = new LongPollingTransport(null, httpClient);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assert.assertEquals(webSocketTransport.getName(), oldTransportName);
                Assert.assertEquals(longPollingTransport.getName(), newTransportName);
                failureLatch.countDown();
            }
        };

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    successLatch.countDown();
                } else {
                    failedLatch.countDown();
                }
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    @Test
    public void testTransportNegotiationFailureForClientWebSocketServerLongPolling() throws Exception {
        startServer(null);
        bayeux.setAllowedTransports("long-polling");

        final ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assert.assertEquals(webSocketTransport.getName(), oldTransportName);
                Assert.assertNull(newTransportName);
                failureLatch.countDown();
            }
        };

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (!message.isSuccessful()) {
                    failedLatch.countDown();
                }
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testTransportNegotiationFailureForClientLongPollingServerCallbackPolling() throws Exception {
        startServer(null);
        // Only callback-polling on server (via extension), only long-polling on client.
        bayeux.setAllowedTransports("long-polling", "callback-polling");
        bayeux.addExtension(new BayeuxServer.Extension.Adapter() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new String[]{"callback-polling"});
                }
                return true;
            }
        });

        final CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient)) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                failureLatch.countDown();
            }
        };
        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (!message.isSuccessful()) {
                    latch.countDown();
                }
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testTransportNegotiationFailureForClientLongPollingServerWebSocket() throws Exception {
        startServer(null);
        bayeux.setAllowedTransports("websocket");

        final ClientTransport longPollingTransport = new LongPollingTransport(null, httpClient);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, longPollingTransport) {
            @Override
            protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
                Assert.assertEquals(longPollingTransport.getName(), oldTransportName);
                Assert.assertEquals(longPollingTransport.getName(), newTransportName);
                failureLatch.countDown();
            }
        };

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (!message.isSuccessful()) {
                    failedLatch.countDown();
                }
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    @Test
    public void testChangeTransportURLOnMetaConnectFailure() throws Exception {
        startServer(null);
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

        final String newURL = "http://localhost:" + connector2.getLocalPort() + cometdServletPath;

        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient)) {
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
        final CountDownLatch latch = new CountDownLatch(1);
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

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
        connector2.stop();
    }

    @Test
    public void testChangeTransportOnMetaConnectFailure() throws Exception {
        startServer(null);

        bayeux.addExtension(new MetaConnectFailureExtension() {
            @Override
            protected boolean onMetaConnect(int count) throws Exception {
                return count != 2;
            }
        });

        final ClientTransport webSocketTransport = new WebSocketTransport(null, null, wsClient);
        LongPollingTransport longPollingTransport = new LongPollingTransport(null, httpClient);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
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
        final CountDownLatch latch = new CountDownLatch(1);
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

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        client.disconnect(1000);
    }

    private abstract class MetaConnectFailureExtension extends BayeuxServer.Extension.Adapter {
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
