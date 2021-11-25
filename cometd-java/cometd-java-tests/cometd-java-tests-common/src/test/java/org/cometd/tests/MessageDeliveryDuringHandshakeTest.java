/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MessageDeliveryDuringHandshakeTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesNotSentInHandshakeResponse(Transport transport) throws Exception {
        startServer(transport);
        BayeuxClient client = newBayeuxClient(transport);
        testMessagesInHandshakeResponse(client, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponse(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(transport, options);
        BayeuxClient client = newBayeuxClient(transport);
        testMessagesInHandshakeResponse(client, true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtension(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(transport, options);
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        BayeuxClient client = newBayeuxClient(transport);
        client.addExtension(new AckExtension());
        testMessagesInHandshakeResponse(client, true);
    }

    private void testMessagesInHandshakeResponse(BayeuxClient client, boolean allowHandshakeMessages) throws Exception {
        String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }
        });

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        ClientSessionChannel.MessageListener listener = (channel, message) -> messages.offer(message);
        client.getChannel(Channel.META_HANDSHAKE).addListener(listener);
        client.getChannel(channelName).addListener(listener);
        client.getChannel(Channel.META_CONNECT).addListener(listener);

        CountDownLatch messagesLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            ServerSessionImpl serverSession = (ServerSessionImpl)bayeux.getSession(client.getId());
            if (serverSession.getQueue().isEmpty() == allowHandshakeMessages)
                messagesLatch.countDown();
        });

        client.handshake();

        Assertions.assertTrue(messagesLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure that the messages arrive in the expected order.
        Message message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNull(message);

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtensionWithDeQueueListener(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(transport, options);
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.addListener((ServerSession.DeQueueListener)(s, queue) -> {
                    while (queue.size() > 1) {
                        queue.poll();
                    }
                });
            }
        });
        BayeuxClient client = newBayeuxClient(transport);
        client.addExtension(new AckExtension());

        String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }
        });

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        ClientSessionChannel.MessageListener listener = (channel, message) -> messages.offer(message);
        client.getChannel(Channel.META_HANDSHAKE).addListener(listener);
        client.getChannel(channelName).addListener(listener);
        client.getChannel(Channel.META_CONNECT).addListener(listener);

        client.handshake();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure that the messages arrive in the expected order.
        Message message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNull(message);

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSecondHandshakeMessagesNotSentInHandshakeResponse(Transport transport) throws Exception {
        startServer(transport);
        BayeuxClient client = newBayeuxClient(transport);
        testSecondHandshakeMessagesInHandshakeResponse(client, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSecondHandshakeMessagesSentInHandshakeResponse(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(transport, options);
        BayeuxClient client = newBayeuxClient(transport);
        testSecondHandshakeMessagesInHandshakeResponse(client, true);
    }

    private void testSecondHandshakeMessagesInHandshakeResponse(BayeuxClient client, boolean allowHandshakeMessages) throws Exception {
        String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }
        });

        // Arrange to make the client send a second handshake.
        AtomicInteger metaConnects = new AtomicInteger();
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession sender, ServerChannel channel, ServerMessage.Mutable message) {
                if (metaConnects.incrementAndGet() == 2) {
                    Map<String, Object> adviceIn = message.getAdvice(true);
                    adviceIn.put(Message.TIMEOUT_FIELD, 0L);
                }
                return true;
            }
        });
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    if (metaConnects.get() == 2) {
                        message.setSuccessful(false);
                        Map<String, Object> advice = message.getAdvice(true);
                        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                    }
                }
                return true;
            }
        });

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        ClientSessionChannel.MessageListener listener = (channel, message) -> messages.offer(message);
        client.getChannel(Channel.META_HANDSHAKE).addListener(listener);
        client.getChannel(Channel.META_CONNECT).addListener(listener);
        client.getChannel(channelName).addListener(listener);

        CountDownLatch messagesLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            ServerSessionImpl serverSession = (ServerSessionImpl)bayeux.getSession(client.getId());
            if (serverSession.getQueue().isEmpty() == allowHandshakeMessages)
                messagesLatch.countDown();
        });

        client.handshake();

        Assertions.assertTrue(messagesLatch.await(5, TimeUnit.SECONDS));

        // Make sure that the messages arrive in the expected order.
        Message message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assertions.assertNull(message);

        disconnectBayeuxClient(client);
    }
}
