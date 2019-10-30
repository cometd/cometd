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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.BayeuxServerImpl;
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

        String channelName = "/own";
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        BayeuxClient client = newBayeuxClient();
        client.handshake(message -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> messageLatch.countDown(), m -> subscribeLatch.countDown());
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
                session.setBroadcastToPublisher(false);
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        LocalSession session = bayeux.newLocalSession("test");
        session.handshake();

        String channelName = "/test";
        CountDownLatch messageLatch = new CountDownLatch(1);
        session.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown());

        session.getChannel(channelName).publish("test");

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        session.disconnect();
    }

    @Test
    public void testBroadcastToPublisherWithServerChannel() throws Exception {
        start(null);

        ServerChannel noBroadcastChannel = bayeux.createChannelIfAbsent("/no_broadcast/deep").getReference();
        noBroadcastChannel.setBroadcastToPublisher(false);
        ServerChannel noBroadcastWildChannel = bayeux.createChannelIfAbsent("/no_broadcast/*").getReference();
        noBroadcastWildChannel.setBroadcastToPublisher(false);

        LocalSession session = bayeux.newLocalSession("test");
        session.handshake();

        CountDownLatch noBroadcastLatch = new CountDownLatch(1);
        ClientSessionChannel noBroadcastClientChannel = session.getChannel(noBroadcastChannel.getId());
        ClientSessionChannel noBroadcastWildClientChannel = session.getChannel(noBroadcastWildChannel.getId());
        session.batch(() -> {
            noBroadcastClientChannel.subscribe((channel, message) -> noBroadcastLatch.countDown());
            noBroadcastWildClientChannel.subscribe((channel, message) -> noBroadcastLatch.countDown());
            noBroadcastClientChannel.publish("no_broadcast");
        });
        Assert.assertFalse(noBroadcastLatch.await(1, TimeUnit.SECONDS));

        ClientSessionChannel broadcastClientChannel = session.getChannel("/test");
        CountDownLatch broadcastLatch = new CountDownLatch(1);
        session.batch(() -> {
            broadcastClientChannel.subscribe((channel, message) -> broadcastLatch.countDown());
            broadcastClientChannel.publish("broadcast");
        });
        Assert.assertTrue(broadcastLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDontBroadcastToPublisherThenServerSideDisconnect() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.BROADCAST_TO_PUBLISHER_OPTION, "false");
        start(options);

        BayeuxClient client = newBayeuxClient();
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> disconnectLatch.countDown());

        client.handshake();

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        ServerSession session = bayeux.getSession(client.getId());
        session.disconnect();

        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
