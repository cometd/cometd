/*
 * Copyright (c) 2008-2015 the original author or authors.
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.transport.AsyncJSONTransport;
import org.cometd.server.transport.JSONTransport;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MessageDeliveryDuringHandshakeTest extends ClientServerTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Object[] data()
    {
        return new Object[]{JSONTransport.class.getName(), AsyncJSONTransport.class.getName()};
    }

    private final String transport;

    public MessageDeliveryDuringHandshakeTest(String transport)
    {
        this.transport = transport;
    }

    @Test
    public void testMessagesSentWithMetaHandshake() throws Exception
    {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.TRANSPORTS_OPTION, transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(options);

        final String channelName = "/handshake_delivery";
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message)
            {
                session.deliver(null, channelName, "data");
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient))
        {
            @Override
            public void onMessages(List<Message.Mutable> messages)
            {
                super.onMessages(messages);
                if (messages.size() > 1)
                {
                    Message.Mutable firstMessage = messages.get(0);
                    if (Channel.META_HANDSHAKE.equals(firstMessage.getChannel()))
                    {
                        Message.Mutable secondMessage = messages.get(1);
                        if (channelName.equals(secondMessage.getChannel()))
                            latch.countDown();
                    }
                }
            }
        };
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMessagesSentWithMetaHandshakeWithAckExtension() throws Exception
    {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.TRANSPORTS_OPTION, transport);
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(options);

        bayeux.addExtension(new AcknowledgedMessagesExtension());

        BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());

        final String channelName = "/handshake_delivery";
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message)
            {
                session.deliver(null, channelName, "data1");
                session.deliver(null, channelName, "data2");
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(555_000, BayeuxClient.State.CONNECTED));
        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        disconnectBayeuxClient(client);
    }
}
