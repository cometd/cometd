/*
 * Copyright (c) 2012 the original author or authors.
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
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.Assert;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PublishCallbackTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testPublishSuccessfulInvokesCallback() throws Exception
    {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertTrue(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Publish again without callback, must not trigger the previous callback
        latch.set(new CountDownLatch(1));
        channel.publish(new HashMap());

        Assert.assertFalse(latch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishNotAllowedInvokesCallback() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
            {
                return false;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishFailedInvokesCallback() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                return false;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
