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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.junit.Assert;
import org.junit.Test;

public class BroadcastToPublisherTest extends ClientServerTest {
    @Test
    public void testBroadcastToPublisher() throws Exception {
        testBroadcastToPublisher(true);
    }

    @Test
    public void testDontBroadcastToPublisher() throws Exception {
        testBroadcastToPublisher(false);
    }

    private void testBroadcastToPublisher(boolean broadcast) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.BROADCAST_TO_PUBLISHER_OPTION, String.valueOf(broadcast));
        start(options);

        final String channelName = "/own";
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final BayeuxClient client = newBayeuxClient();
        client.handshake(message -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> {
                    messageLatch.countDown();
                }, m -> subscribeLatch.countDown());
            }
        });
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        client.getChannel(channelName).publish("test");
        Assert.assertEquals(broadcast, messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDontBroadcastToPublisherWithLocalSessionConfigured() throws Exception {
        start(null);

        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                ((ServerSessionImpl)session).setBroadcastToPublisher(false);
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        LocalSession session = bayeux.newLocalSession("test");
        session.handshake();

        String channelName = "/test";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        session.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown());

        session.getChannel(channelName).publish("test");

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        session.disconnect();
    }
}
