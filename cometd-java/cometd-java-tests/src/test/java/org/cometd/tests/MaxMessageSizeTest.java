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
package org.cometd.tests;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.AbstractServerTransport;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class MaxMessageSizeTest extends AbstractClientServerTest {
    public MaxMessageSizeTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testServerMaxMessageSize() throws Exception {
        int maxMessageSize = 512;
        Map<String, String> options = serverOptions();
        options.put(AbstractServerTransport.MAX_MESSAGE_SIZE_OPTION, String.valueOf(maxMessageSize));
        startServer(options);

        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, 'a');
        final String data = new String(chars);

        final String channelName = "/max_msg";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final CountDownLatch replyLatch = new CountDownLatch(1);
        final BayeuxClient client = newBayeuxClient();
        client.handshake(message -> client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown());
            channel.publish(data, m -> {
                if (!m.isSuccessful()) {
                    replyLatch.countDown();
                }
            });
        }));

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientMaxMessageSize() throws Exception {
        // OkHttp has no way to override the max message size.
        Assume.assumeFalse(transport == Transport.OKHTTP_WEBSOCKET);

        startServer(serverOptions());

        int maxMessageSize = 512;
        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, 'a');
        final String data = new String(chars);

        final String channelName = "/max_msg";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        final BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(clientOptions));
        client.handshake(message -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown(), m -> bayeux.getChannel(channelName).publish(null, data, Promise.noop()));
        });

        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
