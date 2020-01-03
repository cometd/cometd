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

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HighRateServerEventsTest extends ClientServerTest {
    @Before
    public void init() throws Exception {
        start(null);
    }

    @Test
    public void testHighRateServerEvents() throws Exception {
        final String channelName = "/foo";

        LocalSession service = bayeux.newLocalSession("high_rate_test");
        service.handshake();

        final BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        final AtomicInteger messages = new AtomicInteger();
        client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> {
                messages.incrementAndGet();
                latch.get().countDown();
            });
            channel.publish(new HashMap<String, Object>());
        });

        // Wait until subscription is acknowledged
        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        int count = 500;
        messages.set(0);
        latch.set(new CountDownLatch(count));
        for (int i = 0; i < count; ++i) {
            service.getChannel(channelName).publish(new HashMap<String, Object>());
        }
        long end = System.nanoTime();
        Assert.assertTrue(latch.get().await(count * 100, TimeUnit.MILLISECONDS));
        logger.info("rate = {} messages/s", count * 1_000_000_000L / (end - begin));

        assertEquals(count, messages.get());

        disconnectBayeuxClient(client);
    }
}
