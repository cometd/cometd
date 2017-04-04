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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.ext.TimestampExtension;
import org.junit.Assert;
import org.junit.Test;

public class DeliverWithTimeStampExtensionTest extends ClientServerTest {
    @Test
    public void testDeliverWithTimeStampExtension() throws Exception {
        startServer(null);
        bayeux.addExtension(new TimestampExtension());

        final String channelName = "/service/test";
        BayeuxClient client = newBayeuxClient();
        final CountDownLatch messageLatch = new CountDownLatch(1);
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messageLatch.countDown();
            }
        });

        new DeliverService(bayeux, channelName);

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        channel.publish(new HashMap<>());

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    public static class DeliverService extends AbstractService {
        private final String channelName;

        public DeliverService(BayeuxServer bayeux, String channelName) {
            super(bayeux, "test");
            this.channelName = channelName;
            addService(channelName, "process");
        }

        public void process(ServerSession remote, ServerMessage.Mutable message) {
            ServerMessage.Mutable reply = getBayeux().newMessage();
            reply.setChannel(channelName);
            reply.setData("from_server");
            remote.deliver(getServerSession(), reply);
        }
    }
}
