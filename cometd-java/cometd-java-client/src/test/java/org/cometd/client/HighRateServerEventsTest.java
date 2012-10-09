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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HighRateServerEventsTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testHighRateServerEvents() throws Exception
    {
        final String channelName = "/foo";

        LocalSession service = bayeux.newLocalSession("high_rate_test");
        service.handshake();

        final BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(5000, BayeuxClient.State.CONNECTED);

        final AtomicReference<Exception> failure = new AtomicReference<>();
        final AtomicInteger messages = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(2);
        client.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel channel = client.getChannel(channelName);
                channel.subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        messages.incrementAndGet();
                        try
                        {
                            barrier.await();
                        }
                        catch (Exception x)
                        {
                            failure.set(x);
                        }
                    }
                });
                channel.publish(new HashMap<String, Object>());
            }
        });

        // Wait until subscription is acknowledged
        barrier.await();
        assertNull(failure.get());

        long begin = System.nanoTime();
        int count = 500;
        for (int i = 0; i < count; ++i)
        {
            service.getChannel(channelName).publish(new HashMap<String, Object>());
            barrier.await();
            assertNull(failure.get());
        }
        long end = System.nanoTime();
        System.err.println("rate = " + count * 1000000000L / (end - begin) + " messages/s");

        assertEquals(count + 1, messages.get());

        disconnectBayeuxClient(client);
    }
}
