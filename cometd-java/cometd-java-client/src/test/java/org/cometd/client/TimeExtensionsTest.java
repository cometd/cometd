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
package org.cometd.client;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.ext.TimestampClientExtension;
import org.cometd.client.ext.TimesyncClientExtension;
import org.cometd.server.ext.TimestampExtension;
import org.cometd.server.ext.TimesyncExtension;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TimeExtensionsTest extends ClientServerTest {
    @Before
    public void setUp() throws Exception {
        startServer(null);
    }

    @Test
    public void testTimeStamp() throws Exception {
        bayeux.addExtension(new TimestampExtension());

        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new TimestampClientExtension());

        final Queue<Message> messages = new ConcurrentLinkedQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messages.add(message);
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        Assert.assertTrue(client.disconnect(5000));

        Assert.assertTrue(messages.size() > 0);

        for (Message message : messages) {
            Assert.assertTrue(message.get(Message.TIMESTAMP_FIELD) != null);
        }

        disconnectBayeuxClient(client);
    }

    @Test
    public void testTimeSync() throws Exception {
        final TimesyncExtension extension = new TimesyncExtension();
        extension.setAccuracyTarget(0);
        bayeux.addExtension(extension);

        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new TimesyncClientExtension());

        final Queue<Message> messages = new ConcurrentLinkedQueue<>();
        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messages.add(message);
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        Assert.assertTrue(client.disconnect(5000));

        Assert.assertTrue(messages.size() > 0);

        for (Message message : messages) {
            Map<String, Object> ext = message.getExt();
            Assert.assertNotNull(String.valueOf(message), ext);
            Assert.assertNotNull(ext.get("timesync"));
        }

        disconnectBayeuxClient(client);
    }
}
