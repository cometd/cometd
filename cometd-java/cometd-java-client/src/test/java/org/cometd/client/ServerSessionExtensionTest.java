/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class ServerSessionExtensionTest extends ClientServerTest {
    @Test
    public void testServerSessionExtensionDeletingMessage() throws Exception {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        final String channelName = "/delete";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messageLatch.countDown();
            }
        }, new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                subscribeLatch.countDown();
            }
        });
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        ServerSession session = bayeux.getSession(client.getId());
        session.addExtension(new ServerSession.Extension.Adapter() {
            @Override
            public ServerMessage send(ServerSession session, ServerMessage message) {
                if (message.getChannel().equals(channelName)) {
                    return null;
                }
                return message;
            }
        });

        bayeux.getChannel(channelName).publish(null, "data");

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
