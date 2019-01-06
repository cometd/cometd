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
package org.cometd.client;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.DefaultSecurityPolicy;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.junit.Assert;
import org.junit.Test;

public class SubscriptionFailureTest extends ClientServerTest {
    @Test
    public void testSubscriptionFailedOnClientRemovesListener() throws Exception {
        start(null);

        long maxNetworkDelay = 2000;
        final long sleep = maxNetworkDelay + maxNetworkDelay / 2;
        bayeux.getChannel(Channel.META_SUBSCRIBE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException x) {
                    // Ignored
                }
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = (channel, message) -> messageLatch.countDown();
        final CountDownLatch subscriptionLatch = new CountDownLatch(1);
        ClientSession.MessageListener subscriptionCallback = message -> {
            if (!message.isSuccessful()) {
                subscriptionLatch.countDown();
            }
        };
        String channelName = "/echo";
        client.getChannel(channelName).subscribe(messageCallback, subscriptionCallback);
        Assert.assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));

        // Wait for subscription to happen on server.
        Thread.sleep(sleep);

        // Subscription has failed on client, but not on server.
        // Publishing a message on server-side must not be notified on the client.
        bayeux.getChannel(channelName).publish(null, "data", Promise.noop());
        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscriptionFailedOnServerRemovesListener() throws Exception {
        start(null);

        String channelName = "/echo";
        bayeux.createChannelIfAbsent(channelName, (ConfigurableServerChannel.Initializer)channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = (channel, message) -> messageLatch.countDown();
        final CountDownLatch subscriptionLatch = new CountDownLatch(1);
        ClientSession.MessageListener subscriptionCallback = message -> {
            if (!message.isSuccessful()) {
                subscriptionLatch.countDown();
            }
        };
        client.getChannel(channelName).subscribe(messageCallback, subscriptionCallback);
        Assert.assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));

        // Force subscription on server.
        bayeux.getChannel(channelName).subscribe(bayeux.getSession(client.getId()));

        // Subscription has failed on client, but has been forced on server.
        // Publishing a message on server-side must not be notified on the client.
        bayeux.getChannel(channelName).publish(null, "data", Promise.noop());
        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testFailedSubscriptionDecrementsSubscriptionCount() throws Exception {
        start(null);

        final String channelName = "/count";
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                boolean allowed = super.canSubscribe(server, session, channel, message);
                Map<String, Object> ext = message.getExt();
                return allowed && ext != null && (Boolean)ext.get("token");
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicBoolean allowed = new AtomicBoolean();
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_SUBSCRIBE.equals(message.getChannel()) &&
                        channelName.equals(message.get(Message.SUBSCRIPTION_FIELD))) {
                    message.getExt(true).put("token", allowed.get());
                }
                return true;
            }
        });

        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = (channel, message) -> messageLatch.countDown();
        final CountDownLatch failedSubscription = new CountDownLatch(1);
        ClientSession.MessageListener subscriptionCallback = message -> {
            if (!message.isSuccessful()) {
                failedSubscription.countDown();
            }
        };
        // First subscription fails, the subscription count should be
        // decremented to zero so that a subsequent subscribe() could succeed.
        client.getChannel(channelName).subscribe(messageCallback, subscriptionCallback);
        Assert.assertTrue(failedSubscription.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(0, client.getChannel(channelName).getSubscribers().size());
        Assert.assertEquals(0, bayeux.getChannel(channelName).getSubscribers().size());

        // Now allow the subscription, we should be able to subscribe to the same channel.
        allowed.set(true);
        final CountDownLatch succeededSubscription = new CountDownLatch(1);
        subscriptionCallback = message -> {
            if (message.isSuccessful()) {
                succeededSubscription.countDown();
            }
        };
        client.getChannel(channelName).subscribe(messageCallback, subscriptionCallback);
        Assert.assertTrue(succeededSubscription.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(1, client.getChannel(channelName).getSubscribers().size());
        Assert.assertEquals(1, bayeux.getChannel(channelName).getSubscribers().size());

        // Make sure the message can be received.
        bayeux.getChannel(channelName).publish(null, "data", Promise.noop());
        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
