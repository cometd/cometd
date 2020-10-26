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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SessionHijackingTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionHijacking(Transport transport) throws Exception {
        startServer(transport);

        BayeuxClient client1 = newBayeuxClient(transport);
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client2 = newBayeuxClient(transport);
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Client1 tries to impersonate Client2.
        client1.addExtension(new ClientSession.Extension() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                message.setClientId(client2.getId());
                return true;
            }
        });

        AtomicReference<Message> messageRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        String channel = "/session_mismatch";
        client1.getChannel(channel).publish("data", m -> {
            messageRef.set(m);
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Message publishReply = messageRef.get();
        Assertions.assertNotNull(publishReply);
        Assertions.assertFalse(publishReply.isSuccessful());
        Assertions.assertTrue(((String)publishReply.get(Message.ERROR_FIELD)).startsWith("402"));

        // Client2 should be connected.
        Assertions.assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client1);
        disconnectBayeuxClient(client2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionHijackingByBatchedSecondMessage(Transport transport) throws Exception {
        startServer(transport);

        BayeuxClient client1 = newBayeuxClient(transport);
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client2 = newBayeuxClient(transport);
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/hijack";

        // Client1 tries to impersonate Client2.
        client1.addExtension(new ClientSession.Extension() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    message.setClientId(client2.getId());
                }
                return true;
            }
        });

        AtomicReference<Message> messageRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        client1.batch(() -> {
            ClientSessionChannel channel = client1.getChannel(channelName);
            channel.subscribe((c, m) -> {
                // Must not arrive here.
            });
            channel.publish("data", message -> {
                messageRef.set(message);
                latch.countDown();
            });
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Message publishReply = messageRef.get();
        Assertions.assertNotNull(publishReply);
        Assertions.assertFalse(publishReply.isSuccessful());
        Assertions.assertTrue(((String)publishReply.get(Message.ERROR_FIELD)).startsWith("402"));

        // Client2 should be connected.
        Assertions.assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client1);
        disconnectBayeuxClient(client2);
    }
}
