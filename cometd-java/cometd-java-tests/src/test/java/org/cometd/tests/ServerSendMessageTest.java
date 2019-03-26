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
package org.cometd.tests;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.junit.Assert;
import org.junit.Test;

public class ServerSendMessageTest extends AbstractClientServerTest {
    public ServerSendMessageTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testServerCanSendMessageBeforeFirstMetaConnect() throws Exception {
        startServer(serverOptions());

        final String channelName = "/service/test";
        final String response = "response";
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                from.deliver(null, channelName, response, Promise.noop());
                return true;
            }
        });

        BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(null)) {
            @Override
            protected void sendConnect() {
                // Do not send /meta/connect messages to the server.
            }
        };

        final ClientSessionChannel clientChannel = client.getChannel(channelName);
        final CountDownLatch latch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener clientListener = (channel, message) -> {
            if (response.equals(message.getData())) {
                latch.countDown();
            }
        };
        clientChannel.addListener(clientListener);

        client.handshake(message -> clientChannel.publish("request"));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
