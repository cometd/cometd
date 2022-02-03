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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetaConnectOvertakenWithAckExtensionTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectFailureWithAckExtension(Transport transport) throws Exception {
        long timeout = 2000;
        Map<String, String> serverOptions = serverOptions(transport);
        serverOptions.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        start(transport, serverOptions);
        AtomicReference<ServerMessage.Mutable> messageRef = new AtomicReference<>();
        AtomicReference<Promise<ServerMessage.Mutable>> promiseRef = new AtomicReference<>();
        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcvMeta(ServerSession session, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    session.addExtension(new ServerSession.Extension() {
                        private final AtomicInteger connects = new AtomicInteger();

                        @Override
                        public void outgoing(ServerSession sender, ServerSession session, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
                            if (Channel.META_CONNECT.equals(message.getChannel())) {
                                if (connects.incrementAndGet() == 2) {
                                    // Delay the second connect.
                                    messageRef.set(message);
                                    promiseRef.set(promise);
                                    return;
                                }
                            }
                            promise.succeed(message);
                        }
                    });
                }
                return true;
            }
        });
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        String channelName = "/overtaken";

        long maxNetworkDelay = 1000;
        BayeuxClient client = newBayeuxClient(transport);
        client.addExtension(new AckExtension());
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, 0);

        CountDownLatch messageLatch1 = new CountDownLatch(1);
        CountDownLatch messageLatch2 = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = (channel, message) -> {
            if (messageLatch1.getCount() == 0) {
                messageLatch2.countDown();
            } else {
                messageLatch1.countDown();
            }
        };

        CountDownLatch clientSubscribeLatch = new CountDownLatch(1);
        ClientSession.MessageListener subscribeCallback = message -> {
            if (message.isSuccessful()) {
                clientSubscribeLatch.countDown();
            }
        };

        client.handshake(message -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe(messageCallback, subscribeCallback);
            }
        });

        Assertions.assertTrue(clientSubscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the 2nd /meta/connect to be held by the server.
        Thread.sleep(1000);

        // Send a message to deliver it with the ack extension.
        // The 2nd /meta/connect reply will be delayed by the extension on server.
        ServerChannel serverChannel = bayeux.getChannel(channelName);
        serverChannel.publish(null, "data1", Promise.noop());

        // Setup to hold the 3rd /meta/connect on the client, so the 4th is delayed.
        CountDownLatch metaConnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            try {
                metaConnectLatch.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });

        // The client will expire the 2nd /meta/connect and send the 3rd.
        Thread.sleep(timeout + 2 * maxNetworkDelay);

        // The 3rd /meta/connect overtakes the 2nd, and delivers the message.
        Assertions.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));

        // Now the server does not have a /meta/connect outstanding.
        // Publish the 2nd message and resume the processing of the 2nd /meta/connect.
        serverChannel.publish(null, "data2", Promise.noop());
        promiseRef.get().succeed(messageRef.get());
        // The 2nd message must not be delivered because the 2nd /meta/connect has an old batch.
        Assertions.assertFalse(messageLatch2.await(1, TimeUnit.SECONDS));

        // Release to send the 4th /meta/connect.
        metaConnectLatch.countDown();
        // Must receive the 2nd message.
        Assertions.assertTrue(messageLatch2.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
