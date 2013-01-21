/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.server.AbstractServerTransport;
import org.junit.Assert;
import org.junit.Test;

public class LazyChannelAndMessageTest extends ClientServerTest
{
    @Test
    public void testLazyChannelWithGlobalTimeout() throws Exception
    {
        final long globalLazyTimeout = 1000;
        startServer(new HashMap<String, String>(){{
            put(AbstractServerTransport.RANDOMIZE_LAZY_TIMEOUT_OPTION, String.valueOf(false));
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/testLazy";
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
                channel.setLazy(true);
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                subscribeLatch.countDown();
            }
        });
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicLong begin = new AtomicLong();
        client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.getDataAsMap() == null)
                    return;
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
                long accuracy = globalLazyTimeout / 10;
                Assert.assertTrue("Expected " + elapsed + " >= " + globalLazyTimeout, elapsed >= globalLazyTimeout - accuracy);
                latch.countDown();
            }
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        bayeux.getChannel(channelName).publish(null, new HashMap<String, Object>(), null);

        Assert.assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testLazyChannelWithChannelTimeout() throws Exception
    {
        final long channelLazyTimeout = 1000;
        final long globalLazyTimeout = channelLazyTimeout * 4;
        startServer(new HashMap<String, String>(){{
            put(AbstractServerTransport.RANDOMIZE_LAZY_TIMEOUT_OPTION, String.valueOf(false));
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String channelName = "/testLazy";
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setLazyTimeout(channelLazyTimeout);
                channel.setPersistent(true);
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                subscribeLatch.countDown();
            }
        });
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicLong begin = new AtomicLong();
        client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.getDataAsMap() == null)
                    return;
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
                Assert.assertTrue(elapsed < globalLazyTimeout / 2);
                long accuracy = channelLazyTimeout / 10;
                Assert.assertTrue("Expected " + elapsed + " >= " + channelLazyTimeout, elapsed >= channelLazyTimeout - accuracy);
                latch.countDown();
            }
        });
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        bayeux.getChannel(channelName).publish(null, new HashMap<String, Object>(), null);

        Assert.assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testLazyChannelsWithDifferentChannelTimeouts() throws Exception
    {
        final long channelLazyTimeout = 1000;
        final long globalLazyTimeout = channelLazyTimeout * 4;
        startServer(new HashMap<String, String>(){{
            put(AbstractServerTransport.RANDOMIZE_LAZY_TIMEOUT_OPTION, String.valueOf(false));
            put(AbstractServerTransport.MAX_LAZY_TIMEOUT_OPTION, String.valueOf(globalLazyTimeout));
        }});

        String shortLazyChannelName = "/shortLazy";
        bayeux.createIfAbsent(shortLazyChannelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setLazyTimeout(channelLazyTimeout);
                channel.setPersistent(true);
            }
        });

        String longLazyChannelName = "/longLazy";
        bayeux.createIfAbsent(longLazyChannelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setLazyTimeout(globalLazyTimeout);
                channel.setPersistent(true);
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        final CountDownLatch subscribeLatch = new CountDownLatch(2);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                subscribeLatch.countDown();
            }
        });
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicLong begin = new AtomicLong();
        ClientSessionChannel.MessageListener messageListener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.getDataAsMap() == null)
                    return;
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin.get());
                Assert.assertTrue(elapsed < globalLazyTimeout / 2);
                long accuracy = channelLazyTimeout / 10;
                Assert.assertTrue("Expected " + elapsed + " >= " + channelLazyTimeout, elapsed >= channelLazyTimeout - accuracy);
                latch.countDown();
            }
        };
        client.getChannel(shortLazyChannelName).subscribe(messageListener);
        client.getChannel(longLazyChannelName).subscribe(messageListener);
        // Make sure we are subscribed so that there are no
        // pending responses that may return the lazy message
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Wait for the /meta/connect to establish
        TimeUnit.MILLISECONDS.sleep(1000);

        begin.set(System.nanoTime());
        // Cannot publish from the client, as there will always be the "meta"
        // publish response to send, so the lazy message will be sent with it.
        // Send first the long lazy and then the short lazy, to verify that
        // timeouts are properly respected.
        bayeux.getChannel(longLazyChannelName).publish(null, new HashMap<String, Object>(), null);
        bayeux.getChannel(shortLazyChannelName).publish(null, new HashMap<String, Object>(), null);

        Assert.assertTrue(latch.await(2 * globalLazyTimeout, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }
}
