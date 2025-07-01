/*
 * Copyright (c) 2008 the original author or authors.
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

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AckExtensionTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testAckExtensionListener(Transport transport) throws Exception {
        start(transport);

        AcknowledgedMessagesExtension extension = new AcknowledgedMessagesExtension();
        CountDownLatch batchReceiveLatch = new CountDownLatch(1);
        extension.addListener(new AcknowledgedMessagesExtension.Listener() {
            private final Map<ServerSession, Map<Long, List<ServerMessage>>> map = new ConcurrentHashMap<>();

            @Override
            public void onBatchSend(ServerSession session, List<ServerMessage> messages, long batch) {
                map.compute(session, (key, value) -> value != null ? value : new ConcurrentHashMap<>()).put(batch, messages);
            }

            @Override
            public void onBatchReceive(ServerSession session, long batch) {
                Map<Long, List<ServerMessage>> batchedMessages = map.remove(session);
                if (batchedMessages != null) {
                    batchReceiveLatch.countDown();
                }
            }
        });
        bayeuxServer.addExtension(extension);

        String channelName = "/ack_listener";

        BayeuxClient client = newBayeuxClient(transport);
        client.addExtension(new AckExtension());
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client.handshake(hsReply -> {
            if (hsReply.isSuccessful()) {
                ClientSessionChannel clientChannel = client.getChannel(channelName);
                clientChannel.subscribe((channel, message) -> messageLatch.countDown(), reply -> readyLatch.countDown());
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        String sessionId = client.getId();
        ServerSession serverSession = bayeuxServer.getSession(sessionId);
        Assertions.assertNotNull(serverSession);

        // Send a message directly to the client.
        serverSession.deliver(null, channelName, "data", Promise.noop());

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        assertTrue(batchReceiveLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUnacknowledgedMessagesLimit(Transport transport) throws Exception {
        int maxQueue = 5;
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.MAX_QUEUE_OPTION, String.valueOf(maxQueue));
        start(transport, options);

        CountDownLatch batchQueueMaxedLatch = new CountDownLatch(1);
        AcknowledgedMessagesExtension ackExt = new AcknowledgedMessagesExtension();
        ackExt.setMaxQueueSize(maxQueue * 2);
        ackExt.addListener(new AcknowledgedMessagesExtension.Listener() {
            @Override
            public void onBatchQueueMaxed(ServerSession session, Queue<ServerMessage> queue) {
                batchQueueMaxedLatch.countDown();
            }
        });
        bayeuxServer.addExtension(ackExt);

        String channelName = "/unacked-messages-limit";

        // Do not add the client-side ack extension, we will handle it manually.
        BayeuxClient client = newBayeuxClient(transport);

        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    message.getExt(true).put("ack", 1L);
                }
                return true;
            }
        });

        CountDownLatch readyLatch = new CountDownLatch(1);
        client.handshake(Map.of("ext", Map.of("ack", true)), hsReply -> {
            if (hsReply.isSuccessful()) {
                ClientSessionChannel clientChannel = client.getChannel(channelName);
                clientChannel.subscribe((channel, message) -> {
                    // Not interested in receiving messages.
                }, sbReply -> readyLatch.countDown());
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        ServerSession serverSession = bayeuxServer.getSession(client.getId());
        assertNotNull(serverSession);

        client.batch(() -> {
            for (int i = 0; i < maxQueue * 2 + 1; ++i) {
                client.getChannel(channelName).publish("data-" + i);
            }
        });

        assertTrue(batchQueueMaxedLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
