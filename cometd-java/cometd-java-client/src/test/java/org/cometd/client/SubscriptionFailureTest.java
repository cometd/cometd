/*
 * Copyright (c) 2008-2015 the original author or authors.
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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SubscriptionFailureTest extends ClientServerTest
{
    @Test
    public void testSubscriptionFailedOnClientRemovesListener() throws Exception
    {
        startServer(null);

        long maxNetworkDelay = 2000;
        final long sleep = maxNetworkDelay + maxNetworkDelay / 2;
        bayeux.getChannel(Channel.META_SUBSCRIBE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                try
                {
                    Thread.sleep(sleep);
                }
                catch (InterruptedException x)
                {
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
        ClientSessionChannel.MessageListener messageCallback = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.countDown();
            }
        };
        final CountDownLatch subscriptionLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener subscriptionCallback = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
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
        bayeux.getChannel(channelName).publish(null, "data");
        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscriptionFailedOnServerRemovesListener() throws Exception
    {
        startServer(null);

        String channelName = "/echo";
        bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener messageCallback = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.countDown();
            }
        };
        final CountDownLatch subscriptionLatch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener subscriptionCallback = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    subscriptionLatch.countDown();
            }
        };
        client.getChannel(channelName).subscribe(messageCallback, subscriptionCallback);
        Assert.assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));

        // Force subscription on server.
        bayeux.getChannel(channelName).subscribe(bayeux.getSession(client.getId()));

        // Subscription has failed on client, but has been forced on server.
        // Publishing a message on server-side must not be notified on the client.
        bayeux.getChannel(channelName).publish(null, "data");
        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
