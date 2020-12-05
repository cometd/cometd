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
package org.cometd.client.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.HashMapMessage;
import org.cometd.common.TransportException;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BayeuxClientTest extends ClientServerTest {
    @BeforeEach
    public void setUp() throws Exception {
        start(null);
    }

    @Test
    public void testHandshakeFailsBeforeSend() throws Exception {
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                request.listener(new Request.Listener.Adapter() {
                    @Override
                    public void onBegin(Request request) {
                        // Remove the host header so the request will fail
                        request.header(HttpHeader.HOST, null);
                    }
                });
            }
        };
        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        long backOffIncrement = 500;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });
        client.handshake();
        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        Assertions.assertTrue(latch.get().await(backOffIncrement * 2, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(client.disconnect(1000));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assertions.assertFalse(latch.get().await(backOffIncrement * 3, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testHandshakeFailsBadTransport() throws Exception {
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                // Modify the request so that the server chokes it
                request.method(HttpMethod.PUT);
            }
        };
        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        long backOffIncrement = 500;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });
        client.handshake();
        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        Assertions.assertTrue(latch.get().await(backOffIncrement * 2, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(client.disconnect(1000));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assertions.assertFalse(latch.get().await(backOffIncrement * 3, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testHandshakeDenied() throws Exception {
        TestBayeuxClient client = new TestBayeuxClient();
        long backOffIncrement = 500;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                return false;
            }
        });
        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });
        client.handshake();
        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        Assertions.assertFalse(latch.get().await(backOffIncrement * 2, TimeUnit.MILLISECONDS));

        Assertions.assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeFailsNoTransports() throws Exception {
        AtomicReference<Message> handshake = new AtomicReference<>();
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        CountDownLatch connectLatch = new CountDownLatch(1);

        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void processHandshake(Message.Mutable message) {
                // Force no transports
                message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new Object[0]);
                super.processHandshake(message);
            }

            @Override
            protected void sendConnect() {
                super.sendConnect();
                connectLatch.countDown();
            }
        };
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            handshake.set(message);
            handshakeLatch.countDown();
        });
        client.handshake();
        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(handshake.get().isSuccessful());
        Assertions.assertTrue(handshake.get().containsKey(Message.ERROR_FIELD));
        Assertions.assertTrue(client.waitFor(5000, State.DISCONNECTED));

        // Be sure the connect is not tried
        Assertions.assertFalse(connectLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeRetries() throws Exception {
        context.stop();
        TestFilter filter = new TestFilter();
        context.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.start();

        BlockingArrayQueue<Message> queue = new BlockingArrayQueue<>(100, 100);
        AtomicBoolean connected = new AtomicBoolean(false);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            {
                addTransportListener(new TransportListener() {
                    @Override
                    public void onFailure(Throwable failure, List<? extends Message> messages) {
                        Message.Mutable problem = newMessage();
                        problem.setId(newMessageId());
                        problem.setSuccessful(false);
                        problem.put("exception", failure);
                        queue.offer(problem);
                    }
                });
            }
        };

        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connected.set(message.isSuccessful());
            if (message.isSuccessful()) {
                queue.offer(message);
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connected.set(false);
            if (message.isSuccessful()) {
                queue.offer(message);
            }
        });

        client.getChannel("/**").addListener((ClientSessionChannel.MessageListener)(session, message) -> {
            if (!message.isMeta() && !message.isPublishReply() || Channel.META_SUBSCRIBE.equals(message.getChannel()) || Channel.META_DISCONNECT.equals(message.getChannel())) {
                queue.offer(message);
            }
        });

        long backoffIncrement = 1000L;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        filter.code = 503;
        client.handshake();

        Message message = queue.poll(backoffIncrement, TimeUnit.MILLISECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertFalse(message.isSuccessful());
        Object exception = message.get("exception");
        Assertions.assertTrue(exception instanceof TransportException);

        message = queue.poll(2000 + 2 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertFalse(message.isSuccessful());
        exception = message.get("exception");
        Assertions.assertTrue(exception instanceof TransportException);

        message = queue.poll(2000 + 3 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertFalse(message.isSuccessful());
        exception = message.get("exception");
        Assertions.assertTrue(exception instanceof TransportException);

        filter.code = 0;

        message = queue.poll(2000 + 4 * backoffIncrement, TimeUnit.MILLISECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertTrue(message.isSuccessful());
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testConnectRetries() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return connects.incrementAndGet() < 2;
            }
        });

        CountDownLatch attempts = new CountDownLatch(4);
        long backOffIncrement = 1000;
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected boolean scheduleConnect(long interval, long backOff) {
                int count = connects.get();
                if (count > 0) {
                    Assertions.assertEquals((count - 1) * backOffIncrement, backOff);
                    attempts.countDown();
                }
                return super.scheduleConnect(interval, backOff);
            }
        };
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(1));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                connectLatch.get().countDown();
            }
        });
        client.handshake();
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        // BayeuxClient should backoff and retry.
        // Wait for 2 attempts, which will happen at +1s and +3s (doubled for safety).
        Assertions.assertTrue(attempts.await(backOffIncrement * 8, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublish() throws Exception {
        BlockingArrayQueue<String> results = new BlockingArrayQueue<>();

        String channelName = "/chat/msg";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        String data = "Hello World";
        client.getChannel(channelName).publish(data);

        String id = results.poll(10, TimeUnit.SECONDS);
        Assertions.assertEquals(client.getId(), id);
        Assertions.assertEquals(channelName, results.poll(10, TimeUnit.SECONDS));
        Assertions.assertEquals(data, results.poll(10, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testWaitFor() throws Exception {
        BlockingArrayQueue<String> results = new BlockingArrayQueue<>();

        String channelName = "/chat/msg";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        long wait = 1000L;
        long start = System.nanoTime();
        client.handshake(wait);
        long stop = System.nanoTime();
        Assertions.assertTrue(TimeUnit.NANOSECONDS.toMillis(stop - start) < wait);
        Assertions.assertNotNull(client.getId());
        String data = "Hello World";
        client.getChannel(channelName).publish(data);

        Assertions.assertEquals(client.getId(), results.poll(1, TimeUnit.SECONDS));
        Assertions.assertEquals(channelName, results.poll(1, TimeUnit.SECONDS));
        Assertions.assertEquals(data, results.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testWaitForImpliedState() throws Exception {
        BayeuxClient client = newBayeuxClient();
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful() && client.isHandshook()) {
                latch.countDown();
            }
        });

        client.handshake();
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, State.HANDSHAKING));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testURLWithImplicitPort() throws Exception {
        AtomicBoolean listening = new AtomicBoolean();
        try {
            Socket socket = new Socket("localhost", 80);
            socket.close();
            listening.set(true);
        } catch (ConnectException x) {
            listening.set(false);
        }

        CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient("http://localhost/cometd", new JettyHttpClientTransport(null, httpClient));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Assertions.assertFalse(message.isSuccessful());

            // If port 80 is listening, it's probably some other HTTP server
            // and a bayeux request will result in a 404, which is converted
            // to a TransportException; if not listening, it will be a ConnectException
            @SuppressWarnings("unchecked")
            Map<String, Object> failure = (Map<String, Object>)message.get("failure");
            Assertions.assertNotNull(failure);
            Object exception = failure.get("exception");
            if (listening.get()) {
                Assertions.assertTrue(exception instanceof TransportException);
            } else {
                Assertions.assertTrue(exception instanceof ConnectException);
            }
            latch.countDown();
        });
        client.handshake();
        Assertions.assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortNotifiesListeners() throws Exception {
        BayeuxClient client = newBayeuxClient();

        CountDownLatch connectLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (connectLatch.getCount() > 1 && message.isSuccessful() ||
                    connectLatch.getCount() == 1 && !message.isSuccessful()) {
                connectLatch.countDown();
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        // Wait for connect
        Thread.sleep(1000);

        client.abort();

        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortThenRestart() throws Exception {
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(2));
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient));
        client.addTransportListener(new TransportListener() {
            @Override
            public void onSending(List<? extends Message> messages) {
                // Need to be sure that the second connect is sent otherwise
                // the abort and rehandshake may happen before the second
                // connect and the test will fail.
                if (messages.size() == 1 && Channel.META_CONNECT.equals(messages.get(0).getChannel())) {
                    connectLatch.get().countDown();
                }
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        client.abort();
        Assertions.assertFalse(client.isConnected());

        // Restart
        connectLatch.set(new CountDownLatch(2));
        client.handshake();
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortBeforePublishThenRestart() throws Exception {
        String channelName = "/service/test";
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch publishLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected AbstractSessionChannel newChannel(ChannelId channelId) {
                return new BayeuxClientChannel(channelId) {
                    @Override
                    public void publish(Object data) {
                        abort();
                        super.publish(data);
                    }
                };
            }

            @Override
            protected void sendMessages(List<Message.Mutable> messages, Promise<Boolean> promise) {
                super.sendMessages(messages, Promise.from(result -> {
                    promise.succeed(result);
                    if (result && !messages.get(0).getChannelId().isMeta()) {
                        publishLatch.countDown();
                    }
                }, promise::fail));
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connectLatch.get().countDown();
            }
        });
        client.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                failureLatch.countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));
        // Wait for /meta/connect to be held.
        Thread.sleep(1000);

        client.getChannel(channelName).publish(new HashMap<String, Object>());
        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

        // Publish must not be sent
        Assertions.assertFalse(publishLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertFalse(client.isConnected());

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));
        // Check that publish has not been queued and is not sent on restart
        Assertions.assertFalse(publishLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortAfterPublishThenRestart() throws Exception {
        String channelName = "/test";
        AtomicBoolean abort = new AtomicBoolean(false);
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void sendMessages(List<Message.Mutable> messages, Promise<Boolean> promise) {
                if (!messages.get(0).getChannelId().isMeta()) {
                    abort();
                    publishLatch.get().countDown();
                }
                super.sendMessages(messages, promise);
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectLatch.get().await(5, TimeUnit.SECONDS));

        ClientSessionChannel channel = client.getChannel(channelName);
        AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
        channel.subscribe((c, m) -> messageLatch.get().countDown());

        abort.set(true);
        channel.publish(new HashMap<String, Object>());
        Assertions.assertTrue(publishLatch.get().await(10, TimeUnit.SECONDS));
        Assertions.assertFalse(client.isConnected());

        // Message must not be received
        Assertions.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        Assertions.assertTrue(connectLatch.get().await(10, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testRestart() throws Exception {
        BayeuxClient client = newBayeuxClient();

        AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> unconnectedLatch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connectedLatch.get().countDown();
            } else {
                unconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));
        Assertions.assertTrue(client.isConnected());
        Thread.sleep(1000);

        // Stop server
        int port = connector.getLocalPort();
        server.stop();
        Assertions.assertTrue(unconnectedLatch.get().await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, State.UNCONNECTED));
        Assertions.assertFalse(client.isConnected());

        // restart server
        connector.setPort(port);
        connectedLatch.set(new CountDownLatch(1));
        server.start();

        // Wait for connect
        Assertions.assertTrue(connectedLatch.get().await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));
        Assertions.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAuthentication() {
        AtomicReference<String> sessionId = new AtomicReference<>();
        class A extends DefaultSecurityPolicy implements ServerSession.RemovedListener {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                Map<String, Object> ext = message.getExt();
                if (ext == null) {
                    return false;
                }

                Object authn = ext.get("authentication");
                if (!(authn instanceof Map)) {
                    return false;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> authentication = (Map<String, Object>)authn;

                String token = (String)authentication.get("token");
                if (token == null) {
                    return false;
                }

                sessionId.set(session.getId());
                session.addListener(this);

                return true;
            }

            @Override
            public void removed(ServerSession session, ServerMessage message, boolean timeout) {
                sessionId.set(null);
            }
        }
        A authenticator = new A();

        bayeux.setSecurityPolicy(authenticator);
        BayeuxClient client = newBayeuxClient();

        Map<String, Object> authentication = new HashMap<>();
        authentication.put("token", "1234567890");
        Message.Mutable fields = new HashMapMessage();
        fields.getExt(true).put("authentication", authentication);
        client.handshake(fields);

        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        Assertions.assertEquals(client.getId(), sessionId.get());

        disconnectBayeuxClient(client);

        Assertions.assertNull(sessionId.get());
    }

    @Test
    public void testSubscribeToSlashStarStarDoesNotSendMetaMessages() throws Exception {
        stopServer();

        long timeout = 5000;
        Map<String, String> serverParams = new HashMap<>();
        serverParams.put("timeout", String.valueOf(timeout));
        start(serverParams);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));
        // Allow long poll to establish
        Thread.sleep(1000);

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                subscribeLatch.countDown();
            }
        });

        String channelName = "/**";
        CountDownLatch publishLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((channel, message) -> publishLatch.countDown());

        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Register a listener to a service channel, to be sure that
        // they are not broadcasted due to the subscription to /**
        CountDownLatch serviceLatch = new CountDownLatch(1);
        client.getChannel("/service/foo").addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Ignore publish reply, only care about message with data
            if (message.containsKey(Message.DATA_FIELD)) {
                serviceLatch.countDown();
            }
        });

        // Register a listener to /meta/connect, to be sure that /meta/connect messages
        // sent by the client are not broadcasted back due to the subscription to /**
        CountDownLatch metaLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                metaLatch.countDown();
            }
        });

        client.getChannel("/foo").publish(new HashMap<String, Object>());
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        client.getChannel("/service/foo").publish(new HashMap<String, Object>());
        Assertions.assertFalse(serviceLatch.await(1, TimeUnit.SECONDS));

        Assertions.assertFalse(metaLatch.await(timeout + timeout / 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testStateUnSubscribes() throws Exception {
        BlockingArrayQueue<Object> results = new BlockingArrayQueue<>();

        AtomicBoolean failHandShake = new AtomicBoolean(true);

        ClientTransport transport = new JettyHttpClientTransport(null, httpClient) {
            @Override
            protected void customize(Request request) {
                if (failHandShake.compareAndSet(true, false)) {
                    request.listener(new Request.Listener.Adapter() {
                        @Override
                        public void onBegin(Request request) {
                            request.header(HttpHeader.HOST, null);
                        }
                    });
                }
            }
        };

        BayeuxClient client = new BayeuxClient(cometdURL, transport);

        client.getChannel("/meta/*").addListener((ClientSessionChannel.MessageListener)(channel, message) -> results.offer(message));

        client.handshake();

        // Subscribe without waiting
        client.getChannel("/foo/bar").subscribe((channel, message) -> {
        });

        // First handshake fails
        Message message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assertions.assertFalse(message.isSuccessful());

        // Second handshake works
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assertions.assertNotNull(id);

        boolean subscribe = false;
        boolean connect = false;
        for (int i = 0; i < 2; ++i) {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assertions.assertNotNull(message);
            subscribe |= Channel.META_SUBSCRIBE.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        Assertions.assertTrue(subscribe);
        Assertions.assertTrue(connect);

        // Subscribe again
        client.getChannel("/foo/bar").subscribe((c, m) -> {
        });

        // No second subscribe sent, be sure to wait less than the timeout
        // otherwise we get a connect message
        message = (Message)results.poll(5, TimeUnit.SECONDS);
        Assertions.assertNull(message);

        client.disconnect();
        boolean disconnect = false;
        connect = false;
        for (int i = 0; i < 2; ++i) {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assertions.assertNotNull(message);
            disconnect |= Channel.META_DISCONNECT.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        Assertions.assertTrue(disconnect);
        Assertions.assertTrue(connect);
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        // Rehandshake
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        results.clear();
        // Subscribe again
        client.getChannel("/foo/bar").subscribe((c, m) -> {
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel())) {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assertions.assertNotNull(message);
        }
        Assertions.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        // Restart server
        int port = connector.getLocalPort();
        server.stop();
        server.join();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        connector.setPort(port);
        server.start();

        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        results.clear();

        // Subscribe again
        client.getChannel("/foo/bar").subscribe((c, m) -> {
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel())) {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            Assertions.assertNotNull(message);
        }
        Assertions.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeOverHTTPReportsHTTPFailure() throws Exception {
        start(null);
        // No transports on server, to make the client fail
        bayeux.setAllowedTransports();

        BayeuxClient client = newBayeuxClient();
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(hsReply -> {
            // Verify the failure object is there
            @SuppressWarnings("unchecked")
            Map<String, Object> failure = (Map<String, Object>)hsReply.get("failure");
            Assertions.assertNotNull(failure);
            // Verify that the transport is there
            Assertions.assertEquals("long-polling", failure.get(Message.CONNECTION_TYPE_FIELD));
            // Verify the original message is there
            Assertions.assertNotNull(failure.get("message"));
            // Verify the HTTP status code is there
            Assertions.assertEquals(400, failure.get("httpCode"));
            // Verify the exception string is there
            Assertions.assertNotNull(failure.get("exception"));
            // Disconnect to avoid rehandshaking.
            client.disconnect();
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCustomTransportURL() throws Exception {
        start(null);

        ClientTransport transport = new JettyHttpClientTransport(cometdURL, null, httpClient);
        // Pass a bogus URL that must not be used
        BayeuxClient client = new BayeuxClient("http://foo/bar", transport);

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDeliver() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();

        String channelName = "/service/deliver";
        ClientSessionChannel channel = client.getChannel(channelName);
        CountDownLatch latch = new CountDownLatch(1);
        channel.addListener((ClientSessionChannel.MessageListener)(c, message) -> {
            if (!message.isPublishReply()) {
                latch.countDown();
            }
        });
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        channel.publish("data");
        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS));

        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession session, ServerChannel channel, Mutable message) {
                session.deliver(null, channelName, message.getData(), Promise.noop());
                return true;
            }
        });

        channel.publish("data");
        Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMaxMessageSizeExceededViaMetaConnect() throws Exception {
        start(null);

        Map<String, Object> options = new HashMap<>();
        int maxMessageSize = 1024;
        options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(options, httpClient));

        CountDownLatch metaConnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                metaConnectLatch.countDown();
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        String channelName = "/max_message_size";
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Publish a large message from server to client;
        // it will be delivered via /meta/connect, and fail
        // because it's too big, so we'll have a notification
        // to the /meta/connect listener.
        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, '0');
        String data = new String(chars);
        bayeux.getChannel(channelName).publish(null, data, Promise.noop());

        Assertions.assertTrue(metaConnectLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMaxMessageSizeExceededViaPublish() throws Exception {
        start(null);

        Map<String, Object> options = new HashMap<>();
        int maxMessageSize = 1024;
        options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(options, httpClient));

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        String channelName = "/max_message_size";
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Publish a large message that will be echoed back.
        // It will be delivered via the publish, and fail
        // because it's too big.
        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, '0');
        String data = new String(chars);
        CountDownLatch callbackLatch = new CountDownLatch(1);
        client.getChannel(channelName).publish(data, message -> {
            if (!message.isSuccessful()) {
                callbackLatch.countDown();
            }
        });

        Assertions.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private static class TestFilter implements Filter {
        volatile int code = 0;

        @Override
        public void destroy() {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (code != 0) {
                ((HttpServletResponse)response).sendError(code);
            } else {
                chain.doFilter(request, response);
            }
        }

        @Override
        public void init(FilterConfig filterConfig) {
        }
    }

    private class TestBayeuxClient extends BayeuxClient {
        public TestBayeuxClient() {
            super(cometdURL, new JettyHttpClientTransport(null, httpClient));
        }

        // Overridden for visibility.
        @Override
        public State getState() {
            return super.getState();
        }
    }
}
