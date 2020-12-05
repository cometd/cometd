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

import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.WebSocketContainer;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.http.JSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.cometd.bayeux.server.ConfigurableServerChannel.Initializer.Persistent;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testClientRetriesWebSocketTransportIfCannotConnect(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        CountDownLatch connectLatch = new CountDownLatch(2);
        ClientTransport webSocketTransport = newWebSocketTransport(wsType, null);
        webSocketTransport.setOption(JettyWebSocketTransport.CONNECT_TIMEOUT_OPTION, 1000L);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
            @Override
            protected void sendConnect() {
                if ("websocket".equals(getTransport().getName())) {
                    connectLatch.countDown();
                }
                super.sendConnect();
            }
        };

        CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                failedLatch.countDown();
            }
        });

        int port = connector.getLocalPort();
        stopServer();

        client.handshake();
        Assertions.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        prepareServer(wsType, port);
        startServer();

        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testAbortThenRestart(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();

        // Need to be sure that the second connect is sent otherwise
        // the abort and rehandshake may happen before the second
        // connect and the test will fail.
        Thread.sleep(1000);

        client.abort();
        Assertions.assertFalse(client.isConnected());

        // Restart
        CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connectLatch.countDown();
            }
        });
        client.handshake();
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testRestart(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        ClientTransport webSocketTransport = newWebSocketTransport(wsType, null);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport);

        AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && "websocket".equals(client.getTransport().getName())) {
                connectedLatch.get().countDown();
            } else {
                disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        stopServer();
        Assertions.assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertFalse(client.isConnected());

        // restart server
        connectedLatch.set(new CountDownLatch(1));
        prepareServer(wsType, port);
        startServer();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testRestartAfterConnectWithFatalException(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        // ConnectException is a recoverable exception that does not disable the transport.
        // Convert it to a fatal exception so the transport would be disabled.
        // However, since it connected before this fatal exception, the transport is not disabled.
        ClientTransport webSocketTransport;
        switch (wsType) {
            case WEBSOCKET_JSR356:
                webSocketTransport = new org.cometd.client.websocket.javax.WebSocketTransport(null, null, wsClientContainer) {
                    @Override
                    protected Delegate connect(WebSocketContainer container, ClientEndpointConfig configuration, String uri) throws IOException {
                        try {
                            return super.connect(container, configuration, uri);
                        } catch (ConnectException x) {
                            // Convert recoverable exception to unrecoverable.
                            throw new IOException(x);
                        }
                    }
                };
                break;
            case WEBSOCKET_JETTY:
                webSocketTransport = new JettyWebSocketTransport(null, null, wsClient) {
                    @Override
                    protected Delegate connect(WebSocketClient client, ClientUpgradeRequest request, String uri) throws IOException, InterruptedException {
                        try {
                            return super.connect(client, request, uri);
                        } catch (ConnectException x) {
                            // Convert recoverable exception to unrecoverable.
                            throw new IOException(x);
                        }
                    }
                };
                break;
            case WEBSOCKET_OKHTTP:
                webSocketTransport = new OkHttpWebSocketTransport(null, okHttpClient) {
                    @Override
                    protected Delegate connect(String uri, TransportListener listener, List<Message.Mutable> messages) {
                        return super.connect(uri, new TransportListener() {
                            @Override
                            public void onSending(List<? extends Message> messages) {
                                listener.onSending(messages);
                            }

                            @Override
                            public void onMessages(List<Message.Mutable> messages) {
                                listener.onMessages(messages);
                            }

                            @Override
                            public void onFailure(Throwable failure, List<? extends Message> messages) {
                                // Convert recoverable exception to unrecoverable.
                                listener.onFailure(new IOException(failure), messages);
                            }
                        }, messages);
                    }
                };
                break;
            default:
                throw new IllegalArgumentException();
        }

        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && "websocket".equals(client.getTransport().getName())) {
                connectedLatch.get().countDown();
            } else {
                disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        server.stop();
        Assertions.assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertFalse(client.isConnected());

        // restart server
        connector.setPort(port);
        connectedLatch.set(new CountDownLatch(1));
        server.start();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testHandshakeExpiration(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        long maxNetworkDelay = 2000;

        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                try {
                    Thread.sleep(maxNetworkDelay + maxNetworkDelay / 2);
                    return true;
                } catch (InterruptedException x) {
                    return false;
                }
            }
        });

        Map<String, Object> options = new HashMap<>();
        options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(wsType, options);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);
        long backOffIncrement = 1000;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));

        // Expect 2 failed messages because the client backoffs and retries
        // This way we are sure that the late response from the first
        // expired handshake is not delivered to listeners
        CountDownLatch latch = new CountDownLatch(2);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assertions.assertFalse(message.isSuccessful());
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        client.handshake();

        Assertions.assertTrue(latch.await(maxNetworkDelay * 2 + backOffIncrement * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectNotRespondedOnServerSidePublish(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);

        String channelName = "/test";
        AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assertions.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assertions.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectDeliveryOnlyTransport(String wsType) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, "true");
        prepareAndStart(wsType, options);

        BayeuxClient client = newBayeuxClient(wsType);

        String channelName = "/test";
        AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        // from the server-side publish
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectDeliveryOnlySession(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    if (to != null && !to.isLocalSession()) {
                        to.setMetaConnectDeliveryOnly(true);
                    }
                }
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient(wsType);

        String channelName = "/test";
        AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assertions.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectExpires(String wsType) throws Exception {
        long timeout = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        prepareAndStart(wsType, options);

        BayeuxClient client = newBayeuxClient(wsType);
        CountDownLatch connectLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connectLatch.countDown();
            if (connectLatch.getCount() == 0) {
                client.disconnect();
            }
        });
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> disconnectLatch.countDown());
        client.handshake();

        Assertions.assertTrue(connectLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testWebSocketWithAckExtension(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);

        bayeux.addExtension(new AcknowledgedMessagesExtension());
        client.addExtension(new AckExtension());

        String channelName = "/chat/demo";
        BlockingQueue<Message> messages = new BlockingArrayQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> messages.add(m));
            }
        });
        CountDownLatch subscribed = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD))) {
                subscribed.countDown();
            }
        });
        client.handshake();

        Assertions.assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, messages.size());

        ServerChannel chatChannel = bayeux.getChannel(channelName);
        Assertions.assertNotNull(chatChannel);

        int count = 5;
        client.batch(() -> {
            for (int i = 0; i < count; ++i) {
                client.getChannel(channelName).publish("hello_" + i);
            }
        });

        for (int i = 0; i < count; ++i) {
            Assertions.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        // Give time to the /meta/connect to tell the server what is the current ack number
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        connector.stop();
        Thread.sleep(1000);
        Assertions.assertTrue(connector.isStopped());
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send messages while client is offline
        for (int i = count; i < 2 * count; ++i) {
            chatChannel.publish(null, "hello_" + i, Promise.noop());
        }

        Thread.sleep(1000);
        Assertions.assertEquals(0, messages.size());

        connector.setPort(port);
        connector.start();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Check that the offline messages are received
        for (int i = count; i < 2 * count; ++i) {
            Assertions.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        // Send messages while client is online
        client.batch(() -> {
            for (int i = 2 * count; i < 3 * count; ++i) {
                client.getChannel(channelName).publish("hello_" + i);
            }
        });

        // Check if messages after reconnect are received
        for (int i = 2 * count; i < 3 * count; ++i) {
            Assertions.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectDelayedOnServerRespondedBeforeRetry(String wsType) throws Exception {
        long maxNetworkDelay = 2000;
        long backoffIncrement = 2000;
        testMetaConnectDelayedOnServer(wsType, maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement / 2);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testMetaConnectDelayedOnServerRespondedAfterRetry(String wsType) throws Exception {
        long maxNetworkDelay = 2000;
        long backoffIncrement = 1000;
        testMetaConnectDelayedOnServer(wsType, maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement * 2);
    }

    private void testMetaConnectDelayedOnServer(String wsType, long maxNetworkDelay, long backoffIncrement, long delay) throws Exception {
        stopAndDispose();

        Map<String, String> initParams = new HashMap<>();
        long timeout = 5000;
        initParams.put("timeout", String.valueOf(timeout));
        switch (wsType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(wsType, initParams);

        Map<String, Object> options = new HashMap<>();
        options.put("ws.maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(wsType, options);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);

        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                int connects = this.connects.incrementAndGet();
                if (connects == 2) {
                    try {
                        // We delay the second connect, so the client can expire it
                        Thread.sleep(delay);
                    } catch (InterruptedException x) {
                        x.printStackTrace();
                        return false;
                    }
                }
                return true;
            }
        });

        // The second connect must fail, and should never be notified on client
        CountDownLatch connectLatch1 = new CountDownLatch(2);
        CountDownLatch connectLatch2 = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();
            public String failedId;

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                int connects = this.connects.incrementAndGet();
                if (connects == 1 && message.isSuccessful()) {
                    connectLatch1.countDown();
                } else if (connects == 2 && !message.isSuccessful()) {
                    connectLatch1.countDown();
                    failedId = message.getId();
                } else if (connects > 2 && !failedId.equals(message.getId())) {
                    connectLatch2.countDown();
                }
            }
        });

        client.handshake();

        Assertions.assertTrue(connectLatch1.await(timeout + 2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(connectLatch2.await(backoffIncrement * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testClientSendsAndReceivesBigMessage(String wsType) throws Exception {
        int maxMessageSize = 128 * 1024;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("ws.maxMessageSize", String.valueOf(maxMessageSize));
        prepareAndStart(wsType, serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("ws.maxMessageSize", maxMessageSize);
        ClientTransport webSocketTransport = newWebSocketTransport(wsType, clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel("/test");
        CountDownLatch latch = new CountDownLatch(1);
        channel.subscribe((c, m) -> latch.countDown());

        char[] data = new char[maxMessageSize * 3 / 4];
        Arrays.fill(data, 'x');
        channel.publish(new String(data));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testClientDisconnectingClosesTheConnection(String wsType) throws Exception {
        Map<String, String> initParams = new HashMap<>();
        switch (wsType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(wsType, initParams);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect();

        switch (wsType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                CloseLatchWebSocketTransport jsrTransport = (CloseLatchWebSocketTransport)bayeux.getTransport("websocket");
                Assertions.assertTrue(jsrTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            case WEBSOCKET_JETTY:
                CloseLatchJettyWebSocketTransport jettyTransport = (CloseLatchJettyWebSocketTransport)bayeux.getTransport("websocket");
                Assertions.assertTrue(jettyTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testClientDisconnectingSynchronouslyClosesTheConnection(String wsType) throws Exception {
        Map<String, String> initParams = new HashMap<>();
        switch (wsType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(wsType, initParams);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect(1000);

        switch (wsType) {
            case WEBSOCKET_JSR356:
            case WEBSOCKET_OKHTTP:
                CloseLatchWebSocketTransport jsrTransport = (CloseLatchWebSocketTransport)bayeux.getTransport("websocket");
                Assertions.assertTrue(jsrTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            case WEBSOCKET_JETTY:
                CloseLatchJettyWebSocketTransport jettyTransport = (CloseLatchJettyWebSocketTransport)bayeux.getTransport("websocket");
                Assertions.assertTrue(jettyTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    public static class CloseLatchWebSocketTransport extends WebSocketTransport {
        private final CountDownLatch latch = new CountDownLatch(1);

        public CloseLatchWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void onClose(int code, String reason) {
            latch.countDown();
        }
    }

    public static class CloseLatchJettyWebSocketTransport extends org.cometd.server.websocket.jetty.JettyWebSocketTransport {
        private final CountDownLatch latch = new CountDownLatch(1);

        public CloseLatchJettyWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void onClose(int code, String reason) {
            latch.countDown();
        }
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testWhenClientAbortsServerSessionIsSwept(String wsType) throws Exception {
        Map<String, String> options = new HashMap<>();
        long timeout = 2000;
        long maxInterval = 1000;
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        prepareAndStart(wsType, options);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow the /meta/connect to be held.
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(client.getId());
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> latch.countDown());

        client.abort();

        Assertions.assertTrue(latch.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testDisconnectWithPendingMetaConnectWithoutResponseIsFailedOnClient(String wsType) throws Exception {
        long timeout = 2000L;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("timeout", String.valueOf(timeout));
        prepareAndStart(wsType, serverOptions);

        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null) {
                        if (Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD))) {
                            // Pretend this message could not be sent
                            return false;
                        }
                    }
                }
                return true;
            }
        });

        long maxNetworkDelay = 2000L;
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(wsType, clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held on server
        TimeUnit.MILLISECONDS.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        client.disconnect();

        // Only wait for the maxNetworkDelay: when the /meta/disconnect response arrives,
        // the connection is closed, so the /meta/connect is failed on the client side.
        Assertions.assertTrue(latch.await(2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testDeliverDuringHandshakeProcessing(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        String channelName = "/service/test";
        BayeuxClient client = newBayeuxClient(wsType);

        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isPublishReply()) {
                latch.countDown();
            }
        });

        // SessionListener is the first listener notified after the ServerSession is created.
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.deliver(null, channelName, "data", Promise.noop());
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testDeliverDuringHandshakeProcessingWithAckExtension(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        bayeux.addExtension(new AcknowledgedMessagesExtension());

        String channelName = "/service/test";
        BayeuxClient client = newBayeuxClient(wsType);
        client.addExtension(new AckExtension());

        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isPublishReply()) {
                latch.countDown();
            }
        });

        // SessionListener is the first listener notified after the ServerSession is created.
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.deliver(null, channelName, "data", Promise.noop());
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testExtensionIsInvokedAfterNetworkFailure(String wsType) throws Exception {
        // No way to stop OkHttpClient.
        Assumptions.assumeFalse(wsType.equals(WEBSOCKET_OKHTTP));

        prepareAndStart(wsType, null);

        bayeux.addExtension(new AcknowledgedMessagesExtension());

        BayeuxClient client = newBayeuxClient(wsType);
        String channelName = "/test";
        AtomicReference<CountDownLatch> rcv = new AtomicReference<>(new CountDownLatch(1));
        client.addExtension(new AckExtension());
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean rcv(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    rcv.get().countDown();
                }
                return true;
            }
        });
        client.handshake(message -> client.getChannel(channelName).subscribe((c, m) -> {
        }));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // This message will be delivered via /meta/connect.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data1", Promise.noop());
        Assertions.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));
        // Wait for the /meta/connect to be established again.
        Thread.sleep(1000);

        // Stopping HttpClient will also stop the WebSocketClients (both Jetty's and JSR's).
        httpClient.stop();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send a message while disconnected.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data2", Promise.noop());

        rcv.set(new CountDownLatch(1));
        httpClient.start();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
