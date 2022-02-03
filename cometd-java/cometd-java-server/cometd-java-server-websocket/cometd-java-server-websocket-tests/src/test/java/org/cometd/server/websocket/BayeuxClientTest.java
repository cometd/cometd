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
package org.cometd.server.websocket;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BayeuxClientTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testHandshakeDenied(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);
        long backOffIncrement = 500;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));

        SecurityPolicy oldPolicy = bayeux.getSecurityPolicy();
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                return false;
            }
        });

        try {
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

            Assertions.assertTrue(client.waitFor(5000, State.DISCONNECTED));
        } finally {
            bayeux.setSecurityPolicy(oldPolicy);
            disconnectBayeuxClient(client);
        }
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testPublish(String wsType) throws Exception {
        prepareAndStart(wsType, null);

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

        BayeuxClient client = newBayeuxClient(wsType);
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

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testWaitFor(String wsType) throws Exception {
        prepareAndStart(wsType, null);

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

        BayeuxClient client = newBayeuxClient(wsType);
        long wait = 1000L;
        long start = System.nanoTime();
        client.handshake(wait);
        long stop = System.nanoTime();
        Assertions.assertTrue(TimeUnit.NANOSECONDS.toMillis(stop - start) < wait);
        Assertions.assertNotNull(client.getId());

        String data = "Hello World";
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(c, m) -> latch.countDown());
        client.getChannel(channelName).publish(data);

        Assertions.assertEquals(client.getId(), results.poll(1, TimeUnit.SECONDS));
        Assertions.assertEquals(channelName, results.poll(1, TimeUnit.SECONDS));
        Assertions.assertEquals(data, results.poll(1, TimeUnit.SECONDS));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testAuthentication(String wsType) throws Exception {
        prepareAndStart(wsType, null);

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

        SecurityPolicy oldPolicy = bayeux.getSecurityPolicy();
        bayeux.setSecurityPolicy(authenticator);
        try {
            BayeuxClient client = newBayeuxClient(wsType);

            Map<String, Object> authentication = new HashMap<>();
            authentication.put("token", "1234567890");
            Message.Mutable fields = new HashMapMessage();
            fields.getExt(true).put("authentication", authentication);
            client.handshake(fields);

            Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

            Assertions.assertEquals(client.getId(), sessionId.get());

            disconnectBayeuxClient(client);

            Assertions.assertNull(sessionId.get());
        } finally {
            bayeux.setSecurityPolicy(oldPolicy);
        }
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testClient(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        BayeuxClient client = newBayeuxClient(wsType);

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            logger.info("<< {} @ {}", message, channel);
            if (message.isSuccessful()) {
                handshakeLatch.countDown();
            }
        });
        CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            logger.info("<< {} @ {}", message, channel);
            if (message.isSuccessful()) {
                connectLatch.countDown();
            }
        });
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            logger.info("<< {} @ {}", message, channel);
            if (message.isSuccessful()) {
                subscribeLatch.countDown();
            }
        });
        CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            logger.info("<< {} @ {}", message, channel);
            if (message.isSuccessful()) {
                unsubscribeLatch.countDown();
            }
        });

        client.handshake();
        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch publishLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener subscriber = (channel, message) -> {
            logger.info(" < {} @ {}", message, channel);
            publishLatch.countDown();
        };
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        String data = "data";
        aChannel.publish(data);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        aChannel.unsubscribe(subscriber);
        Assertions.assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Disabled("TODO: verify why it does not work; I suspect the setAllowedTransport() does not play since the WSUpgradeFilter kicks in first")
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testHandshakeOverWebSocketReportsHTTPFailure(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        // No transports on server, to make the client fail
        bayeux.setAllowedTransports();

        BayeuxClient client = newBayeuxClient(wsType);
        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Verify the failure object is there
            @SuppressWarnings("unchecked")
            Map<String, Object> failure = (Map<String, Object>)message.get("failure");
            Assertions.assertNotNull(failure);
            // Verify that the transport is there
            Assertions.assertEquals("websocket", failure.get(Message.CONNECTION_TYPE_FIELD));
            // Verify the original message is there
            Assertions.assertNotNull(failure.get("message"));
            // Verify the HTTP status code is there
            Assertions.assertEquals(400, failure.get("httpCode"));
            // Verify the exception string is there
            Assertions.assertNotNull(failure.get("exception"));
            latch.countDown();
        });
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    // The test filter is not called because the WebSocketUpgradeFilter is added first.
    // Also, the WS implementation adds headers directly to HttpFields, and flushes the
    // response so they cannot be removed/intercepted before they are sent to the client.
    @Disabled
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testWebSocketResponseHeadersRemoved(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        context.addFilter(new FilterHolder(new Filter() {
            @Override
            public void init(FilterConfig filterConfig) {
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                try {
                    // Wrap the response to remove the header
                    chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse)response) {
                        @Override
                        public void addHeader(String name, String value) {
                            if (!"Sec-WebSocket-Accept".equals(name)) {
                                super.addHeader(name, value);
                            }
                        }
                    });
                } finally {
                    ((HttpServletResponse)response).setHeader("Sec-WebSocket-Accept", null);
                }
            }

            @Override
            public void destroy() {
            }
        }), cometdServletPath, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        ClientTransport webSocketTransport = newWebSocketTransport(wsType, null);
        ClientTransport longPollingTransport = newLongPollingTransport(null);
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport);

        CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                Assertions.assertEquals(JettyHttpClientTransport.NAME, client.getTransport().getName());
                latch.countDown();
            }
        });
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testCustomTransportURL(String wsType) throws Exception {
        prepareAndStart(wsType, null);

        ClientTransport transport = newWebSocketTransport(wsType, cometdURL, null);
        // Pass a bogus URL that must not be used
        BayeuxClient client = new BayeuxClient("http://foo/bar", transport);

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, State.CONNECTED));

        disconnectBayeuxClient(client);
    }
}
