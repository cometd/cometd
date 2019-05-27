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

import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.WebSocketContainer;

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
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.transport.JSONTransport;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.cometd.bayeux.server.ConfigurableServerChannel.Initializer.Persistent;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest {
    public BayeuxClientWebSocketTest(String implementation) {
        super(implementation);
    }

    @Before
    public void init() throws Exception {
        prepareAndStart(null);
    }

    @Test
    public void testClientRetriesWebSocketTransportIfCannotConnect() throws Exception {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        webSocketTransport.setOption(JettyWebSocketTransport.CONNECT_TIMEOUT_OPTION, 1000L);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport) {
            @Override
            protected void sendConnect() {
                if ("websocket".equals(getTransport().getName())) {
                    connectLatch.countDown();
                }
                super.sendConnect();
            }
        };

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                failedLatch.countDown();
            }
        });

        int port = connector.getLocalPort();
        stopServer();

        client.handshake();
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        prepareServer(port, null, true);
        startServer();

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortThenRestart() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();

        // Need to be sure that the second connect is sent otherwise
        // the abort and rehandshake may happen before the second
        // connect and the test will fail.
        Thread.sleep(1000);

        client.abort();
        Assert.assertFalse(client.isConnected());

        // Restart
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connectLatch.countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testRestart() throws Exception {
        ClientTransport webSocketTransport = newWebSocketTransport(null);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport);

        final AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && "websocket".equals(client.getTransport().getName())) {
                connectedLatch.get().countDown();
            } else {
                disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        stopServer();
        Assert.assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(!client.isConnected());

        // restart server
        connectedLatch.set(new CountDownLatch(1));
        prepareServer(port, null, true);
        startServer();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testRestartAfterConnectWithFatalException() throws Exception {
        // ConnectException is a recoverable exception that does not disable the transport.
        // Convert it to a fatal exception so the transport would be disabled.
        // However, since it connected before this fatal exception, the transport is not disabled.
        ClientTransport webSocketTransport;
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
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
            default:
                throw new IllegalArgumentException();
        }

        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        final AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && "websocket".equals(client.getTransport().getName())) {
                connectedLatch.get().countDown();
            } else {
                disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        server.stop();
        Assert.assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(!client.isConnected());

        // restart server
        connector.setPort(port);
        connectedLatch.set(new CountDownLatch(1));
        server.start();

        // Wait for connect
        Assert.assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeExpiration() throws Exception {
        final long maxNetworkDelay = 2000;

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
        ClientTransport webSocketTransport = newWebSocketTransport(options);
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);
        long backOffIncrement = 1000;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));

        // Expect 2 failed messages because the client backoffs and retries
        // This way we are sure that the late response from the first
        // expired handshake is not delivered to listeners
        final CountDownLatch latch = new CountDownLatch(2);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assert.assertFalse(message.isSuccessful());
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(latch.await(maxNetworkDelay * 2 + backOffIncrement * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectNotRespondedOnServerSidePublish() throws Exception {
        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        final ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is not responded
        Assert.assertFalse(connectLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlyTransport() throws Exception {
        stopAndDispose();

        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, "true");
        prepareAndStart(options);

        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

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

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDeliveryOnlySession() throws Exception {
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    if (to != null && !to.isLocalSession()) {
                        ((ServerSessionImpl)to).setMetaConnectDeliveryOnly(true);
                    }
                }
                return true;
            }
        });

        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/test";
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(metaHandshake, handshake) -> {
            if (handshake.isSuccessful()) {
                client.getChannel(channelName).subscribe((channel, message) -> publishLatch.get().countDown());
            }
        });
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connectLatch.get().countDown());
        client.handshake();

        // Wait for the long poll to establish
        Thread.sleep(1000);

        // Test publish triggered by an external event
        final LocalSession emitter = bayeux.newLocalSession("test_emitter");
        emitter.handshake();
        final String data = "test_data";
        bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // Test publish triggered by a message sent by the client
        // There will be a response pending so the case is different
        publishLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        String serviceChannelName = "/service/test";
        final ServerChannel serviceChannel = bayeux.createChannelIfAbsent(serviceChannelName, new Persistent()).getReference();
        serviceChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(channelName).publish(emitter, data, Promise.noop());
                return true;
            }
        });
        client.getChannel(serviceChannelName).publish(new HashMap<>());

        Assert.assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));
        // Make sure long poll is responded
        Assert.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectExpires() throws Exception {
        stopAndDispose();
        long timeout = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        prepareAndStart(options);

        final BayeuxClient client = newBayeuxClient();
        final CountDownLatch connectLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connectLatch.countDown();
            if (connectLatch.getCount() == 0) {
                client.disconnect();
            }
        });
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> disconnectLatch.countDown());
        client.handshake();

        Assert.assertTrue(connectLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));
        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testWebSocketWithAckExtension() throws Exception {
        final BayeuxClient client = newBayeuxClient();

        bayeux.addExtension(new AcknowledgedMessagesExtension());
        client.addExtension(new AckExtension());

        final String channelName = "/chat/demo";
        final BlockingQueue<Message> messages = new BlockingArrayQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> messages.add(m));
            }
        });
        final CountDownLatch subscribed = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD))) {
                subscribed.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, messages.size());

        final ServerChannel chatChannel = bayeux.getChannel(channelName);
        Assert.assertNotNull(chatChannel);

        final int count = 5;
        client.batch(() -> {
            for (int i = 0; i < count; ++i) {
                client.getChannel(channelName).publish("hello_" + i);
            }
        });

        for (int i = 0; i < count; ++i) {
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        // Give time to the /meta/connect to tell the server what is the current ack number
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        connector.stop();
        Thread.sleep(1000);
        Assert.assertTrue(connector.isStopped());
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send messages while client is offline
        for (int i = count; i < 2 * count; ++i) {
            chatChannel.publish(null, "hello_" + i, Promise.noop());
        }

        Thread.sleep(1000);
        Assert.assertEquals(0, messages.size());

        connector.setPort(port);
        connector.start();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Check that the offline messages are received
        for (int i = count; i < 2 * count; ++i) {
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        // Send messages while client is online
        client.batch(() -> {
            for (int i = 2 * count; i < 3 * count; ++i) {
                client.getChannel(channelName).publish("hello_" + i);
            }
        });

        // Check if messages after reconnect are received
        for (int i = 2 * count; i < 3 * count; ++i) {
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());
        }

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMetaConnectDelayedOnServerRespondedBeforeRetry() throws Exception {
        final long maxNetworkDelay = 2000;
        final long backoffIncrement = 2000;
        testMetaConnectDelayedOnServer(maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement / 2);
    }

    @Test
    public void testMetaConnectDelayedOnServerRespondedAfterRetry() throws Exception {
        final long maxNetworkDelay = 2000;
        final long backoffIncrement = 1000;
        testMetaConnectDelayedOnServer(maxNetworkDelay, backoffIncrement, maxNetworkDelay + backoffIncrement * 2);
    }

    private void testMetaConnectDelayedOnServer(final long maxNetworkDelay, final long backoffIncrement, final long delay) throws Exception {
        stopAndDispose();

        Map<String, String> initParams = new HashMap<>();
        long timeout = 5000;
        initParams.put("timeout", String.valueOf(timeout));
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(initParams);

        Map<String, Object> options = new HashMap<>();
        options.put("ws.maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(options);
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
        final CountDownLatch connectLatch1 = new CountDownLatch(2);
        final CountDownLatch connectLatch2 = new CountDownLatch(1);
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

        Assert.assertTrue(connectLatch1.await(timeout + 2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
        Assert.assertTrue(connectLatch2.await(backoffIncrement * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientSendsAndReceivesBigMessage() throws Exception {
        stopAndDispose();

        int maxMessageSize = 128 * 1024;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("ws.maxMessageSize", String.valueOf(maxMessageSize));
        prepareAndStart(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("ws.maxMessageSize", maxMessageSize);
        ClientTransport webSocketTransport = newWebSocketTransport(clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel("/test");
        final CountDownLatch latch = new CountDownLatch(1);
        channel.subscribe((c, m) -> latch.countDown());

        char[] data = new char[maxMessageSize * 3 / 4];
        Arrays.fill(data, 'x');
        channel.publish(new String(data));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientDisconnectingClosesTheConnection() throws Exception {
        stopAndDispose();

        Map<String, String> initParams = new HashMap<>();
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(initParams);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect();

        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                CloseLatchWebSocketTransport jsrTransport = (CloseLatchWebSocketTransport)bayeux.getTransport("websocket");
                Assert.assertTrue(jsrTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            case WEBSOCKET_JETTY:
                CloseLatchJettyWebSocketTransport jettyTransport = (CloseLatchJettyWebSocketTransport)bayeux.getTransport("websocket");
                Assert.assertTrue(jettyTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    @Test
    public void testClientDisconnectingSynchronouslyClosesTheConnection() throws Exception {
        stopAndDispose();

        Map<String, String> initParams = new HashMap<>();
        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                initParams.put("transports", CloseLatchWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            case WEBSOCKET_JETTY:
                initParams.put("transports", CloseLatchJettyWebSocketTransport.class.getName() + "," + JSONTransport.class.getName());
                break;
            default:
                throw new IllegalArgumentException();
        }
        prepareAndStart(initParams);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Thread.sleep(1000);

        client.disconnect(1000);

        switch (wsTransportType) {
            case WEBSOCKET_JSR_356:
                CloseLatchWebSocketTransport jsrTransport = (CloseLatchWebSocketTransport)bayeux.getTransport("websocket");
                Assert.assertTrue(jsrTransport.latch.await(5, TimeUnit.SECONDS));
                break;
            case WEBSOCKET_JETTY:
                CloseLatchJettyWebSocketTransport jettyTransport = (CloseLatchJettyWebSocketTransport)bayeux.getTransport("websocket");
                Assert.assertTrue(jettyTransport.latch.await(5, TimeUnit.SECONDS));
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

    @Test
    public void testWhenClientAbortsServerSessionIsSwept() throws Exception {
        stopAndDispose();

        Map<String, String> options = new HashMap<>();
        long timeout = 2000;
        long maxInterval = 1000;
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        prepareAndStart(options);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow the /meta/connect to be held.
        Thread.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(client.getId());
        session.addListener((ServerSession.RemoveListener)(s, t) -> latch.countDown());

        client.abort();

        Assert.assertTrue(latch.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDisconnectWithPendingMetaConnectWithoutResponseIsFailedOnClient() throws Exception {
        stopAndDispose();

        final long timeout = 2000L;
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put("timeout", String.valueOf(timeout));
        prepareAndStart(serverOptions);

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

        final long maxNetworkDelay = 2000L;
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put("maxNetworkDelay", maxNetworkDelay);
        ClientTransport webSocketTransport = newWebSocketTransport(clientOptions);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport);

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held on server
        TimeUnit.MILLISECONDS.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        client.disconnect();

        // Only wait for the maxNetworkDelay: when the /meta/disconnect response arrives,
        // the connection is closed, so the /meta/connect is failed on the client side.
        Assert.assertTrue(latch.await(2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testDeliverDuringHandshakeProcessing() throws Exception {
        final String channelName = "/service/test";
        BayeuxClient client = newBayeuxClient();

        final CountDownLatch latch = new CountDownLatch(1);
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

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDeliverDuringHandshakeProcessingWithAckExtension() throws Exception {
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        final String channelName = "/service/test";
        BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());

        final CountDownLatch latch = new CountDownLatch(1);
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

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testExtensionIsInvokedAfterNetworkFailure() throws Exception {
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        final BayeuxClient client = newBayeuxClient();
        final String channelName = "/test";
        final AtomicReference<CountDownLatch> rcv = new AtomicReference<>(new CountDownLatch(1));
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
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // This message will be delivered via /meta/connect.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data1", Promise.noop());
        Assert.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));
        // Wait for the /meta/connect to be established again.
        Thread.sleep(1000);

        // Stopping HttpClient will also stop the WebSocketClients (both Jetty's and JSR's).
        httpClient.stop();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send a message while disconnected.
        bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data2", Promise.noop());

        rcv.set(new CountDownLatch(1));
        httpClient.start();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(rcv.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
