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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.junit.Assert;
import org.junit.Test;

public class ServerChannelMessageListenerTest extends ClientServerTest {
    @Test
    public void testBroadcastMessageProcessingHalted() throws Exception {
        testMessageProcessingHalted("/broadcast");
    }

    @Test
    public void testServiceMessageProcessingHalted() throws Exception {
        testMessageProcessingHalted("/service/foo");
    }

    private void testMessageProcessingHalted(String channelName) throws Exception {
        start(null);
        ServerChannel serverChannel = bayeux.createChannelIfAbsent(channelName).getReference();
        serverChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                return false;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        final AtomicReference<Message> messageRef = new AtomicReference<>();
        final CountDownLatch messageLatch = new CountDownLatch(1);
        client.getChannel(channelName).publish("data", message -> {
            messageRef.set(message);
            messageLatch.countDown();
        });

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        Message message = messageRef.get();
        Assert.assertFalse(message.isSuccessful());
        Assert.assertTrue(message.containsKey(Message.ERROR_FIELD));
    }
}
