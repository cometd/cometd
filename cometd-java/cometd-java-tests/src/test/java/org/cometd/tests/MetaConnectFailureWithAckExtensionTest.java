/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Assert;
import org.junit.Test;

public class MetaConnectFailureWithAckExtensionTest extends AbstractClientServerTest {
    public MetaConnectFailureWithAckExtensionTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testMetaConnectFailureWithAckExtension() throws Exception {
        startServer(serverOptions());
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        final String channelName = "/test";

        final CountDownLatch serverSubscribeLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SubscriptionListener() {
            @Override
            public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
                if (channelName.equals(channel.getId())) {
                    serverSubscribeLatch.countDown();
                }
            }

            @Override
            public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            }
        });

        final long delay = 1000;
        final long maxNetworkDelay = 3 * delay;
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
                    bayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data");
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

        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, 0);

        final CountDownLatch messageLatch1 = new CountDownLatch(1);
        final CountDownLatch messageLatch2 = new CountDownLatch(1);
        final ClientSessionChannel.MessageListener messageCallback = new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (messageLatch1.getCount() == 0) {
                    messageLatch2.countDown();
                }
                messageLatch1.countDown();
            }
        };

        final CountDownLatch clientSubscribeLatch = new CountDownLatch(1);
        final ClientSessionChannel.MessageListener subscribeCallback = new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    clientSubscribeLatch.countDown();
                }
            }
        };

        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    client.getChannel(channelName).subscribe(messageCallback, subscribeCallback);
                }
            }
        });

        Assert.assertTrue(clientSubscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch1.await(2 * maxNetworkDelay, TimeUnit.MILLISECONDS));
        Assert.assertFalse(messageLatch2.await(2 * delay, TimeUnit.MILLISECONDS));

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
