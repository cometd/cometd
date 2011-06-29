/*
 * Copyright (c) 2010 the original author or authors.
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
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServerChannelSubscribeUnsubscribeTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testUnsubscribeSubscribeBroadcast() throws Exception
    {
        final String actionField = "action";
        final String unsubscribeAction = "unsubscribe";
        final String subscribeAction = "subscribe";
        final String testChannelName = "/test";
        final String systemChannelName = "/service/system";

        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final CountDownLatch resubscribeLatch = new CountDownLatch(1);
        new AbstractService(bayeux, "test")
        {
            {
                addService(systemChannelName, "processSystemMessage");
            }

            public void processSystemMessage(ServerSession session, ServerMessage message)
            {
                Map<String, Object> data = message.getDataAsMap();
                String action = (String)data.get(actionField);
                if (unsubscribeAction.equals(action))
                {
                    boolean unsubscribed = getBayeux().getChannel(testChannelName).unsubscribe(session);
                    if (unsubscribed)
                        unsubscribeLatch.countDown();
                }
                else if (subscribeAction.equals(action))
                {
                    boolean subscribed = getBayeux().getChannel(testChannelName).subscribe(session);
                    if (subscribed)
                        resubscribeLatch.countDown();
                }
            }
        };

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        ClientSessionChannel testChannel = client.getChannel(testChannelName);
        client.startBatch();
        testChannel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.get().countDown();
            }
        });
        testChannel.publish(new HashMap<String, Object>());
        client.endBatch();
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Tell the server to unsubscribe the session
        Map<String, Object> unsubscribe = new HashMap<String, Object>();
        unsubscribe.put(actionField, unsubscribeAction);
        ClientSessionChannel systemChannel = client.getChannel(systemChannelName);
        systemChannel.publish(unsubscribe);
        Assert.assertTrue(unsubscribeLatch.await(1, TimeUnit.SECONDS));

        // Publish, must not receive it
        messageLatch.set(new CountDownLatch(1));
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Tell the server to resubscribe the session
        Map<String, Object> resubscribe = new HashMap<String, Object>();
        resubscribe.put(actionField, subscribeAction);
        systemChannel.publish(resubscribe);
        Assert.assertTrue(resubscribeLatch.await(1, TimeUnit.SECONDS));

        // Publish, must receive it
        messageLatch.set(new CountDownLatch(1));
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testUnsubscribeSubscribeService() throws Exception
    {
        final String testChannelName = "/service/test";
        new AbstractService(bayeux, "test")
        {
            {
                addService(testChannelName, "processServiceMessage");
            }

            public void processServiceMessage(ServerSession session, ServerMessage.Mutable message)
            {
                session.deliver(getServerSession(), message);
            }
        };

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    subscribeLatch.countDown();
            }
        });
        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        ClientSessionChannel testChannel = client.getChannel(testChannelName);
        testChannel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.get().countDown();
            }
        });
        Assert.assertTrue(subscribeLatch.await(1, TimeUnit.SECONDS));

        // Publish, must receive it
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Tell the server to unsubscribe the session
        Assert.assertTrue(bayeux.getChannel(testChannelName).unsubscribe(bayeux.getSession(client.getId())));

        // Publish, must receive it (service channels are always invoked)
        messageLatch.set(new CountDownLatch(1));
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Tell the server to resubscribe the session
        Assert.assertTrue(bayeux.getChannel(testChannelName).subscribe(bayeux.getSession(client.getId())));

        // Publish, must receive it
        messageLatch.set(new CountDownLatch(1));
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testUnsubscribeDisconnectSubscribe() throws Exception
    {
        final String actionField = "action";
        final String unsubscribeAction = "unsubscribe";
        final String testChannelName = "/test";
        final String systemChannelName = "/service/system";

        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final AtomicReference<ServerSession> sessionRef = new AtomicReference<ServerSession>();
        new AbstractService(bayeux, "test")
        {
            {
                addService(systemChannelName, "processSystemMessage");
            }

            public void processSystemMessage(ServerSession session, ServerMessage message)
            {
                Map<String, Object> data = message.getDataAsMap();
                String action = (String)data.get(actionField);
                if (unsubscribeAction.equals(action))
                {
                    boolean unsubscribed = getBayeux().getChannel(testChannelName).unsubscribe(session);
                    if (unsubscribed)
                    {
                        sessionRef.set(session);
                        unsubscribeLatch.countDown();
                    }
                }
            }
        };

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        ClientSessionChannel testChannel = client.getChannel(testChannelName);
        client.startBatch();
        testChannel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.get().countDown();
            }
        });
        testChannel.publish(new HashMap<String, Object>());
        client.endBatch();
        Assert.assertTrue(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Tell the server to unsubscribe the session
        Map<String, Object> unsubscribe = new HashMap<String, Object>();
        unsubscribe.put(actionField, unsubscribeAction);
        ClientSessionChannel systemChannel = client.getChannel(systemChannelName);
        systemChannel.publish(unsubscribe);
        Assert.assertTrue(unsubscribeLatch.await(1, TimeUnit.SECONDS));

        // Publish, must not receive it
        messageLatch.set(new CountDownLatch(1));
        testChannel.publish(new HashMap<String, Object>());
        Assert.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        // Disconnect
        Assert.assertTrue(client.disconnect(1000));

        final ServerSession serverSession = sessionRef.get();
        Assert.assertNotNull(serverSession);

        Assert.assertFalse(bayeux.getChannel(testChannelName).subscribe(serverSession));

        disconnectBayeuxClient(client);
    }
}
