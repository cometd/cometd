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
package org.cometd.tests;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
        bayeux.addExtension(extension);

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

        Assertions.assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        String sessionId = client.getId();
        ServerSession serverSession = bayeux.getSession(sessionId);
        Assertions.assertNotNull(serverSession);

        // Send a message directly to the client.
        serverSession.deliver(null, channelName, "data", Promise.noop());

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(batchReceiveLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
