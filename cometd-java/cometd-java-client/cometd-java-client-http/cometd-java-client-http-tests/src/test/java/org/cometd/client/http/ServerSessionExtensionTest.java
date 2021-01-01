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
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServerSessionExtensionTest extends ClientServerTest {
    @Test
    public void testServerSessionExtensionDeletingMessage() throws Exception {
        start(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        String channelName = "/delete";
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        ServerSession session = bayeux.getSession(client.getId());
        session.addExtension(new ServerSession.Extension() {
            @Override
            public ServerMessage send(ServerSession sender, ServerSession session, ServerMessage message) {
                if (message.getChannel().equals(channelName)) {
                    return null;
                }
                return message;
            }
        });

        bayeux.getChannel(channelName).publish(null, "data", Promise.noop());

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
