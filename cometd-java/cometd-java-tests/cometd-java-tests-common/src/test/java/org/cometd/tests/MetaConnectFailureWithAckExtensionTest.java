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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetaConnectFailureWithAckExtensionTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectFailureWithAckExtension(Transport transport) throws Exception {
        start(transport);
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        String channelName = "/test";

        CountDownLatch serverSubscribeLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SubscriptionListener() {
            @Override
            public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
                if (channelName.equals(channel.getId())) {
                    serverSubscribeLatch.countDown();
                }
            }
        });

        long delay = 1000;
        long maxNetworkDelay = 3 * delay;
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                // The test must slow down the first connect enough
                // for the maxNetworkDelay to expire on client, and
                // the second connect enough to allow the first connect
                // to return but not to expire the second connect.

                // Be sure we are subscribed.
                try {
                    serverSubscribeLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException x) {
                    // Ignored
                }

                int connect = connects.incrementAndGet();
                if (connect == 1) {
                    // Publish a message on the first connect, which will fail.
                    // The ack extension will deliver it via /meta/connect.
                    bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data", Promise.noop());
                    sleep(maxNetworkDelay + delay);
                } else if (connect == 2) {
                    // When the second connect arrives, maxNetworkDelay has elapsed.
                    // We need to wait at least one delay to allow the first connect
                    // to return and then some more to ensure no races.
                    sleep(2 * delay);
                }
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient(transport);
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Constant(0));
        client.addExtension(new AckExtension());
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);

        CountDownLatch messageLatch1 = new CountDownLatch(1);
        CountDownLatch messageLatch2 = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = (channel, message) -> {
            if (messageLatch1.getCount() == 0) {
                messageLatch2.countDown();
            }
            messageLatch1.countDown();
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
        Assertions.assertTrue(messageLatch1.await(2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
        Assertions.assertFalse(messageLatch2.await(2 * delay, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    protected void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            // Ignore
        }
    }
}
