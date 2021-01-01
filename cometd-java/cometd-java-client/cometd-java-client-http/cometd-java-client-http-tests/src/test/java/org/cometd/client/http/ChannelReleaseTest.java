/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.client.http;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ChannelReleaseTest extends ClientServerTest {
    @Test
    public void testChannelReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        boolean released = channel.release();

        Assertions.assertTrue(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assertions.assertNotNull(newChannel);
        Assertions.assertNotSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenersNotReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.ClientSessionChannelListener() {
        });
        boolean released = channel.release();

        Assertions.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assertions.assertNotNull(newChannel);
        Assertions.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberNotReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        client.batch(() -> {
            channel.subscribe((c, m) -> latch.countDown());
            channel.publish("");
        });
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        boolean released = channel.release();

        Assertions.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assertions.assertNotNull(newChannel);
        Assertions.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenerRemovedIsReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.ClientSessionChannelListener listener = new ClientSessionChannel.ClientSessionChannelListener() {
        };
        channel.addListener(listener);
        boolean released = channel.release();

        Assertions.assertFalse(released);

        channel.removeListener(listener);
        Assertions.assertTrue(channel.getListeners().isEmpty());
        released = channel.release();

        Assertions.assertTrue(released);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberRemovedIsReleased() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.MessageListener listener = (c, m) -> latch.countDown();
        client.batch(() -> {
            channel.subscribe(listener);
            channel.publish("");
        });
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        boolean released = channel.release();

        Assertions.assertFalse(released);

        CountDownLatch unsubscribe = new CountDownLatch(1);
        client.getChannel(Channel.META_UNSUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(c, m) -> unsubscribe.countDown());
        channel.unsubscribe(listener);
        Assertions.assertTrue(unsubscribe.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(channel.getSubscribers().isEmpty());
        released = channel.release();

        Assertions.assertTrue(released);

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
        Assertions.assertTrue(channel.release());
        Assertions.assertTrue(channel.isReleased());

        ClientSessionChannel.ClientSessionChannelListener channelListener = new ClientSessionChannel.ClientSessionChannelListener() {
        };
        try {
            channel.addListener(channelListener);
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.removeListener(channelListener);
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.setAttribute("foo", "bar");
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.removeAttribute("foo");
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.getAttributeNames();
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        ClientSessionChannel.MessageListener listener = (c, m) -> {
        };
        try {
            channel.subscribe(listener);
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.unsubscribe(listener);
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.unsubscribe();
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.publish("");
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        try {
            channel.getSession();
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }

        disconnectBayeuxClient(client);
    }
}
