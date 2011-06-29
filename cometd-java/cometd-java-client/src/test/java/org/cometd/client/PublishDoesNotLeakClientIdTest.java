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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PublishDoesNotLeakClientIdTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testPublishDoesNotLeakClientId() throws Exception
    {
        BayeuxClient client1 = newBayeuxClient();
        client1.handshake();
        try
        {
            assertTrue(client1.waitFor(1000, BayeuxClient.State.CONNECTED));

            BayeuxClient client2 = newBayeuxClient();
            client2.handshake();
            try
            {
                assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

                assertFalse(client1.getId().equals(client2.getId()));

                String channel = "/test";
                final CountDownLatch subscribe = new CountDownLatch(1);
                client1.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        subscribe.countDown();
                    }
                });
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<Message> messageRef = new AtomicReference<Message>();
                client1.getChannel(channel).subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messageRef.set(message);
                        latch.countDown();
                    }
                });
                assertTrue(subscribe.await(1000, TimeUnit.MILLISECONDS));

                client2.getChannel(channel).publish(client2.newMessage());

                assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
                assertNull(messageRef.get().getClientId());
            }
            finally
            {
                disconnectBayeuxClient(client2);
            }
        }
        finally
        {
            disconnectBayeuxClient(client1);
        }
    }
}
