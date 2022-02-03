/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.TimestampClientExtension;
import org.cometd.client.ext.TimesyncClientExtension;
import org.cometd.server.ext.TimestampExtension;
import org.cometd.server.ext.TimesyncExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TimeExtensionsTest extends ClientServerTest {
    @BeforeEach
    public void setUp() throws Exception {
        start(null);
    }

    @Test
    public void testTimeStamp() throws Exception {
        bayeux.addExtension(new TimestampExtension());

        BayeuxClient client = newBayeuxClient();
        client.addExtension(new TimestampClientExtension());

        Queue<Message> messages = new ConcurrentLinkedQueue<>();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> messages.add(message));

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        Assertions.assertTrue(client.disconnect(5000));

        Assertions.assertTrue(messages.size() > 0);

        for (Message message : messages) {
            Assertions.assertNotNull(message.get(Message.TIMESTAMP_FIELD));
        }

        disconnectBayeuxClient(client);
    }

    @Test
    public void testTimeSync() throws Exception {
        TimesyncExtension extension = new TimesyncExtension();
        extension.setAccuracyTarget(0);
        bayeux.addExtension(extension);

        BayeuxClient client = newBayeuxClient();
        client.addExtension(new TimesyncClientExtension());

        Queue<Message> messages = new ConcurrentLinkedQueue<>();
        client.getChannel("/meta/*").addListener((ClientSessionChannel.MessageListener)(channel, message) -> messages.add(message));

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        Assertions.assertTrue(client.disconnect(5000));

        Assertions.assertTrue(messages.size() > 0);

        for (Message message : messages) {
            Map<String, Object> ext = message.getExt();
            Assertions.assertNotNull(ext, String.valueOf(message));
            Assertions.assertNotNull(ext.get("timesync"));
        }

        disconnectBayeuxClient(client);
    }
}
