/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.junit.Assert;
import org.junit.Test;

public class SubscriptionTest extends ClientServerTest {
    @Test
    public void testSubscriptionToMetaChannelFails() throws Exception {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        String channelName = Channel.META_CONNECT;
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.subscribe((c, m) -> {
        }, message -> {
            Assert.assertFalse(message.isSuccessful());
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, channel.getSubscribers().size());
        Assert.assertEquals(0, bayeux.getChannel(channelName).getSubscribers().size());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscriptionToServiceChannelIsANoOperation() throws Exception {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/service/test";
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messageLatch.countDown();
            }
        };
        channel.subscribe(listener, message -> {
            Assert.assertTrue(message.isSuccessful());
            subscribeLatch.countDown();
        });

        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(1, channel.getSubscribers().size());
        Assert.assertEquals(0, bayeux.getChannel(channelName).getSubscribers().size());

        channel.publish("test");

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        channel.unsubscribe(listener, message -> {
            Assert.assertTrue(message.isSuccessful());
            unsubscribeLatch.countDown();
        });

        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, channel.getSubscribers().size());

        disconnectBayeuxClient(client);
    }
}
