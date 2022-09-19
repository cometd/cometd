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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OrphanedSessionTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testForOrphanedSessionDeliverDoesNotEnqueue(Transport transport) throws Exception {
        long maxInterval = 2000;
        Map<String, String> serverOptions = serverOptions(transport);
        serverOptions.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        start(transport, serverOptions);

        AtomicReference<ServerSession> serverSessionRef = new AtomicReference<>();
        CountDownLatch removedLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                serverSessionRef.set(session);
                removedLatch.countDown();
            }
        });

        BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(transport, null)) {
            @Override
            protected void sendConnect() {
                // Do not send /meta/connect messages to the server.
            }
        };
        client.handshake();

        Assertions.assertTrue(removedLatch.await(2 * maxInterval, TimeUnit.SECONDS));

        // This session is now orphaned.
        ServerSession serverSession = serverSessionRef.get();

        // Add a queue listener to make sure messages are not enqueued.
        CountDownLatch queueLatch = new CountDownLatch(1);
        serverSession.addListener((ServerSession.QueueListener)(sender, message) -> queueLatch.countDown());

        // Call deliver() to send a message.
        CountDownLatch deliverLatch = new CountDownLatch(1);
        serverSession.deliver(null, "/orphan", "orphan", Promise.complete((delivered, failure) -> {
            // The message should not be delivered.
            if (!delivered && failure == null) {
                deliverLatch.countDown();
            }
        }));

        Assertions.assertFalse(queueLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertTrue(deliverLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
