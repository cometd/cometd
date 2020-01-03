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
package org.cometd.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.junit.Assert;
import org.junit.Test;

public class ChannelReleaseTest extends ClientServerTest {
    @Test
    public void testChannelReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        boolean released = channel.release();

        Assert.assertTrue(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertNotSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenersNotReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.ClientSessionChannelListener() {
        });
        boolean released = channel.release();

        Assert.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberNotReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        String channelName = "/foo";
        final ClientSessionChannel channel = client.getChannel(channelName);
        client.batch(() -> {
            channel.subscribe((c, m) -> latch.countDown());
            channel.publish("");
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        boolean released = channel.release();

        Assert.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenerRemovedIsReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.ClientSessionChannelListener listener = new ClientSessionChannel.ClientSessionChannelListener() {
        };
        channel.addListener(listener);
        boolean released = channel.release();

        Assert.assertFalse(released);

        channel.removeListener(listener);
        Assert.assertTrue(channel.getListeners().isEmpty());
        released = channel.release();

        Assert.assertTrue(released);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberRemovedIsReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        String channelName = "/foo";
        final ClientSessionChannel channel = client.getChannel(channelName);
        final ClientSessionChannel.MessageListener listener = (c, m) -> latch.countDown();
        client.batch(() -> {
            channel.subscribe(listener);
            channel.publish("");
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        boolean released = channel.release();

        Assert.assertFalse(released);

        final CountDownLatch unsubscribe = new CountDownLatch(1);
        client.getChannel(Channel.META_UNSUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> unsubscribe.countDown());
        channel.unsubscribe(listener);
        Assert.assertTrue(unsubscribe.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(channel.getSubscribers().isEmpty());
        released = channel.release();

        Assert.assertTrue(released);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testReleasedChannelCannotOperate() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        Assert.assertTrue(channel.release());
        Assert.assertTrue(channel.isReleased());

        ClientSessionChannel.ClientSessionChannelListener channelListener = new ClientSessionChannel.ClientSessionChannelListener() {
        };
        try {
            channel.addListener(channelListener);
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.removeListener(channelListener);
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.setAttribute("foo", "bar");
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.removeAttribute("foo");
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.getAttributeNames();
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        ClientSessionChannel.MessageListener listener = (c, m) -> {
        };
        try {
            channel.subscribe(listener);
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.unsubscribe(listener);
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.unsubscribe();
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.publish("");
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.getSession();
            Assert.fail();
        } catch (IllegalStateException expected) {
        }

        disconnectBayeuxClient(client);
    }
}
