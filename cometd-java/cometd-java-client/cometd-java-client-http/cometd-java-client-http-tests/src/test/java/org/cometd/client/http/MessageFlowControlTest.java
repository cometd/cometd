/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.ext.TimestampExtension;
import org.junit.Assert;
import org.junit.Test;

public class MessageFlowControlTest extends ClientServerTest {
    @Test
    public void testMessageFlowControlWithDeQueueListener() throws Exception {
        start(null);
        testMessageFlowControlWithDeQueueListener(false, -1);
    }

    @Test
    public void testMessageFlowControlWithDeQueueListenerAndLazyChannel() throws Exception {
        Map<String, String> initParams = new HashMap<>();
        long maxLazyTimeout = 1000;
        initParams.put("maxLazyTimeout", String.valueOf(maxLazyTimeout));
        start(initParams);
        testMessageFlowControlWithDeQueueListener(true, maxLazyTimeout);
    }

    public void testMessageFlowControlWithDeQueueListener(boolean lazyChannel, long maxLazyTimeout) throws Exception {
        bayeux.addExtension(new TimestampExtension("yyyyMMddHHmmss"));

        String channelName = "/test";
        bayeux.createChannelIfAbsent(channelName, channel -> {
            channel.setPersistent(true);
            if (lazyChannel) {
                channel.setLazy(true);
            }
        });

        int totalMessages = 8;
        CountDownLatch queuedMessages = new CountDownLatch(totalMessages);
        long toleranceSeconds = 2;
        AtomicInteger keptMessages = new AtomicInteger();
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.addListener((ServerSession.DeQueueListener)(s, queue) -> {
                    long lastTimeStamp = 0;
                    for (Iterator<ServerMessage> iterator = queue.iterator(); iterator.hasNext(); ) {
                        ServerMessage message1 = iterator.next();
                        if (channelName.equals(message1.getChannel())) {
                            long timeStamp = Long.parseLong(message1.get(Message.TIMESTAMP_FIELD).toString());
                            if (timeStamp <= lastTimeStamp + toleranceSeconds) {
                                iterator.remove();
                            } else {
                                keptMessages.incrementAndGet();
                                lastTimeStamp = timeStamp;
                            }
                            queuedMessages.countDown();
                        }
                    }
                });
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll to establish
        Thread.sleep(1000);

        CountDownLatch subscribed = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> subscribed.countDown());
        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        client.getChannel(channelName).subscribe((channel, message) -> messages.offer(message));
        Assert.assertTrue(subscribed.await(5, TimeUnit.SECONDS));

        // Publishing a message result in the long poll being woken up, which in turn will
        // drain the queue. There is a race between the publish of messages below and the
        // resumed long poll to access the queue, so it's possible that the messages are
        // drained from the queue in more than one pass (e.g. publish0, publish1, drain -
        // keep publish0, discard publish1 - publish2, ... publishN, drain - keep publish2,
        // discard publish3 to publishN). We take this in account when asserting below.

        for (int i = 0; i < totalMessages; ++i) {
            bayeux.getChannel(channelName).publish(null, "msg_" + i, Promise.noop());
        }
        // Wait for all the message to be processed on server side,
        // to avoids a race to access variable keptMessages
        Assert.assertTrue(queuedMessages.await(5, TimeUnit.SECONDS));

        if (lazyChannel) {
            Thread.sleep(maxLazyTimeout);
        }

        for (int i = 0; i < keptMessages.get(); ++i) {
            Assert.assertNotNull(messages.poll(1, TimeUnit.SECONDS));
        }
        Assert.assertNull(messages.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
