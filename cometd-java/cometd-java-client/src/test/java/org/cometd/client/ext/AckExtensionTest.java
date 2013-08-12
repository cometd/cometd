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

package org.cometd.client.ext;

import java.io.EOFException;
import java.net.ConnectException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ClientServerTest;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AckExtensionTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testAck() throws Exception
    {
        LongPollingTransport transport = new LongPollingTransport(null, httpClient);
        final BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                if (!(x instanceof EOFException || x instanceof ConnectException))
                    super.onFailure(x, messages);
            }
        };

        bayeux.addExtension(new AcknowledgedMessagesExtension());
        client.addExtension(new AckExtension());

        final String channelName = "/chat/demo";
        final BlockingQueue<Message> messages = new BlockingArrayQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                {
                    client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            messages.add(message);
                        }
                    });
                }
            }
        });
        final CountDownLatch subscribed = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful() && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD)))
                    subscribed.countDown();
            }
        });
        client.handshake();

        Assert.assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, messages.size());

        final ServerChannel chatChannel = bayeux.getChannel(channelName);
        Assert.assertNotNull(chatChannel);

        final int count = 5;
        client.batch(new Runnable()
        {
            public void run()
            {
                for (int i = 0; i < count; ++i)
                    client.getChannel(channelName).publish("hello_" + i);
            }
        });

        for (int i = 0; i < count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        // Wait for the long poll to happen
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        connector.stop();

        // Wait for connections to process the remote close
        Thread.sleep(1000);

        Assert.assertTrue(connector.isStopped());
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Send messages while client is offline
        for (int i = count; i < 2 * count; ++i)
            chatChannel.publish(null, "hello_" + i);

        Thread.sleep(1000);
        Assert.assertEquals(0, messages.size());

        connector.setPort(port);
        connector.start();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Check that the offline messages are received
        for (int i = count; i < 2 * count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        // Send messages while client is online
        client.batch(new Runnable()
        {
            public void run()
            {
                for (int i = 2 * count; i < 3 * count; ++i)
                    client.getChannel(channelName).publish("hello_" + i);
            }
        });

        // Check if messages after reconnect are received
        for (int i = 2 * count; i < 3 * count; ++i)
            Assert.assertEquals("hello_" + i, messages.poll(5, TimeUnit.SECONDS).getData());

        disconnectBayeuxClient(client);
    }
}
