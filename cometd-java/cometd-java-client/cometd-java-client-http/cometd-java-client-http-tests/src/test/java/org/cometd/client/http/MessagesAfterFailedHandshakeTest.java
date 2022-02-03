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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MessagesAfterFailedHandshakeTest extends ClientServerTest {
    @BeforeEach
    public void init() throws Exception {
        start(null);
        bayeux.setSecurityPolicy(new Policy());
    }

    @Test
    public void testSubscribeAfterFailedHandshake() throws Exception {
        CountDownLatch serverLatch = new CountDownLatch(1);
        String channelName = "/test_subscribe_after_failed_handshake";
        bayeux.getChannel(Channel.META_SUBSCRIBE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                serverLatch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> {
                });
                handshakeLatch.countDown();
            }
        });
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_SUBSCRIBE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                subscribeLatch.countDown();
            }
        });
        client.handshake();

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(serverLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishAfterFailedHandshake() throws Exception {
        CountDownLatch serverLatch = new CountDownLatch(1);
        String channelName = "/test_subscribe_after_failed_handshake";
        MarkedReference<ServerChannel> channel = bayeux.createChannelIfAbsent(channelName);
        channel.getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                serverLatch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (!m.isSuccessful()) {
                client.getChannel(channelName).publish(new HashMap<String, Object>());
                handshakeLatch.countDown();
            }
        });
        CountDownLatch publishLatch = new CountDownLatch(1);
        client.getChannel(channelName).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (!m.isSuccessful()) {
                publishLatch.countDown();
            }
        });
        client.handshake();

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(serverLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private static class Policy extends DefaultSecurityPolicy {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
            Map<String, Object> ext = message.getExt();
            return ext != null && ext.get("authn") != null;
        }
    }
}
