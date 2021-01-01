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
package org.cometd.tests;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Assert;
import org.junit.Test;

public class MessageDeliveryDuringHandshakeTest extends AbstractClientServerTest {
    public MessageDeliveryDuringHandshakeTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testMessagesNotSentInHandshakeResponse() throws Exception {
        startServer(serverOptions());
        BayeuxClient client = newBayeuxClient();
        testMessagesInHandshakeResponse(client, false);
    }

    @Test
    public void testMessagesSentInHandshakeResponse() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(options);
        BayeuxClient client = newBayeuxClient();
        testMessagesInHandshakeResponse(client, true);
    }

    @Test
    public void testMessagesSentInHandshakeResponseWithAckExtension() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(options);
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());
        testMessagesInHandshakeResponse(client, true);
    }

    private void testMessagesInHandshakeResponse(BayeuxClient client, final boolean allowHandshakeMessages) throws Exception {
        final String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        final CountDownLatch messagesLatch = new CountDownLatch(1);
        final ServerChannel metaConnectChannel = bayeux.getChannel(Channel.META_CONNECT);
        metaConnectChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                // Check the queue when receiving the first /meta/connect.
                if (((ServerSessionImpl)from).getQueue().isEmpty() == allowHandshakeMessages) {
                    messagesLatch.countDown();
                }
                metaConnectChannel.removeListener(this);
                return true;
            }
        });

        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        ClientSessionChannel.MessageListener listener = (channel, message) -> messages.offer(message);
        client.getChannel(Channel.META_HANDSHAKE).addListener(listener);
        client.getChannel(channelName).addListener(listener);
        client.getChannel(Channel.META_CONNECT).addListener(listener);

        client.handshake();

        Assert.assertTrue(messagesLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure that the messages arrive in the expected order.
        Message message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(message);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMessagesSentInHandshakeResponseWithAckExtensionWithDeQueueListener() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        startServer(options);
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.addListener((ServerSession.DeQueueListener)(s, queue) -> {
                    while (queue.size() > 1) {
                        queue.poll();
                    }
                });
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });
        BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());

        final String channelName = "/test";
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        ClientSessionChannel.MessageListener listener = (channel, message) -> messages.offer(message);
        client.getChannel(Channel.META_HANDSHAKE).addListener(listener);
        client.getChannel(channelName).addListener(listener);
        client.getChannel(Channel.META_CONNECT).addListener(listener);

        client.handshake();

        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure that the messages arrive in the expected order.
        Message message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(channelName, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_CONNECT, message.getChannel());
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(message);

        disconnectBayeuxClient(client);
    }
}
