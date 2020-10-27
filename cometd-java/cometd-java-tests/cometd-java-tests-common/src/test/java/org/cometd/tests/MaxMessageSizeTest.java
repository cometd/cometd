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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MaxMessageSizeTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testServerMaxMessageSize(Transport transport) throws Exception {
        int maxMessageSize = 512;
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.MAX_MESSAGE_SIZE_OPTION, String.valueOf(maxMessageSize));
        startServer(transport, options);

        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, 'a');
        String data = new String(chars);

        String channelName = "/max_msg";
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch replyLatch = new CountDownLatch(1);
        BayeuxClient client = newBayeuxClient(transport);
        client.handshake(message -> client.batch(() -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown());
            channel.publish(data, m -> {
                if (!m.isSuccessful()) {
                    replyLatch.countDown();
                }
            });
        }));

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertTrue(replyLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientMaxMessageSize(Transport transport) throws Exception {
        // OkHttp has no way to override the max message size.
        Assumptions.assumeFalse(transport == Transport.OKHTTP_WEBSOCKET || transport == Transport.OKHTTP_HTTP);

        startServer(transport);

        int maxMessageSize = 512;
        char[] chars = new char[maxMessageSize];
        Arrays.fill(chars, 'a');
        String data = new String(chars);

        String channelName = "/max_msg";
        CountDownLatch messageLatch = new CountDownLatch(1);
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(transport, clientOptions));
        client.handshake(message -> {
            ClientSessionChannel channel = client.getChannel(channelName);
            channel.subscribe((c, m) -> messageLatch.countDown(), m -> bayeux.getChannel(channelName).publish(null, data, Promise.noop()));
        });

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
