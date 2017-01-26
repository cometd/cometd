/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class MessagesInHandshakeResponseTest extends ClientServerTest {
    @Test
    public void testMessagesNotSentInHandshakeResponse() throws Exception {
        startServer(null);

        final String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.deliver(null, channelName, "data");
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        BayeuxClient client = newBayeuxClient();

        final CountDownLatch messageLatch = new CountDownLatch(1);
        final CountDownLatch handshakeLatch = new CountDownLatch(1);

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertEquals(1, messageLatch.getCount());
                handshakeLatch.countDown();
            }
        });
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertEquals(0, handshakeLatch.getCount());
                messageLatch.countDown();
            }
        });
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
