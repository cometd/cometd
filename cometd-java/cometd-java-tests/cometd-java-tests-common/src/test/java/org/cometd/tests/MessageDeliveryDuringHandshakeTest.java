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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
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
        start(transport);
        BayeuxClient client = newBayeuxClient(transport);
        testMessagesInHandshakeResponse(client, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponse(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        start(transport, options);
        BayeuxClient client = newBayeuxClient(transport);
        testMessagesInHandshakeResponse(client, true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtension(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        start(transport, options);
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

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener handshakeListener = (channel, message) -> handshakeLatch.countDown();
        client.getChannel(Channel.META_HANDSHAKE).addListener(handshakeListener);
        CountDownLatch channelLatch = new CountDownLatch(2);
        ClientSessionChannel.MessageListener channelListener = (channel, message) -> channelLatch.countDown();
        client.getChannel(channelName).addListener(channelListener);
        CountDownLatch connectLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener connectListener = (channel, message) -> connectLatch.countDown();
        client.getChannel(Channel.META_CONNECT).addListener(connectListener);

        CountDownLatch queueLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            ServerSessionImpl serverSession = (ServerSessionImpl)bayeux.getSession(client.getId());
            if (serverSession.getQueue().isEmpty() == allowHandshakeMessages)
                queueLatch.countDown();
        });

        client.handshake();

        Assertions.assertTrue(queueLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(channelLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtensionWithDeQueueListener(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        start(transport, options);
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

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener handshakeListener = (channel, message) -> handshakeLatch.countDown();
        client.getChannel(Channel.META_HANDSHAKE).addListener(handshakeListener);
        CountDownLatch channelLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener channelListener = (channel, message) -> channelLatch.countDown();
        client.getChannel(channelName).addListener(channelListener);
        CountDownLatch connectLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener connectListener = (channel, message) -> connectLatch.countDown();
        client.getChannel(Channel.META_CONNECT).addListener(connectListener);

        client.handshake();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(channelLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
