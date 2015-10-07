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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.transport.AsyncJSONTransport;
import org.cometd.server.transport.JSONTransport;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class FastReconnectTest extends ClientServerTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object[]> data()
    {
        Object[][] data =
        {
                {JSONTransport.class.getName()},
                {AsyncJSONTransport.class.getName()}
        };
        return Arrays.asList(data);
    }

    private final String transport;

    public FastReconnectTest(String transport)
    {
        this.transport = transport;
    }

    @Test
    public void testMessagesSentWithMetaHandshake() throws Exception
    {
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.TRANSPORTS_OPTION, transport);
        options.put(AbstractServerTransport.FAST_RECONNECT_OPTION, String.valueOf(true));
        startServer(options);

        final String channelName = "/fast_reconnect";
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
    public void testReconnectUsingHandshake() throws Exception
    {
        long timeout = 1500;
        long maxInterval = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.TRANSPORTS_OPTION, transport);
        options.put(AbstractServerTransport.FAST_RECONNECT_OPTION, String.valueOf(true));
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(options);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        // Stop the connector try to reconnect
        int port = connector.getLocalPort();
        connector.stop();

        // Add a /meta/handshake listener to be sure we reconnect using handshake.
        final CountDownLatch handshakeReconnect = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                // Reconnecting using handshake, first failure.
                if (!message.isSuccessful())
                    handshakeReconnect.countDown();
            }
        });

        // Wait for the session to be swept (timeout + maxInterval).
        final CountDownLatch sessionRemoved = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message)
            {
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout)
            {
                sessionRemoved.countDown();
            }
        });

        Assert.assertTrue(sessionRemoved.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));
        Assert.assertTrue(handshakeReconnect.await(10 * client.getBackoffIncrement(), TimeUnit.MILLISECONDS));

        // Restart the connector.
        connector.setPort(port);
        connector.start();

        Assert.assertTrue(client.waitFor(20 * client.getBackoffIncrement(), BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }
}
