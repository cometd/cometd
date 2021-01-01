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
package org.cometd.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConcurrentDisconnectSubscribePublishTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectSubscribe(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        AtomicBoolean subscribed = new AtomicBoolean(false);
        bayeux.addListener(new BayeuxServer.SubscriptionListener() {
            @Override
            public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
                subscribed.set(true);
            }
        });

        AtomicBoolean serviced = new AtomicBoolean(false);
        new MetaSubscribeService(bayeux, serviced);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        // Forge a bad sequence of messages to simulate concurrent arrival of disconnect and subscribe messages
        String channel = "/foo";
        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "},{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertFalse(subscribed.get(), "not subscribed");
        Assertions.assertFalse(serviced.get(), "not serviced");
        // The response to the subscribe must be that the session is unknown.
        Assertions.assertTrue(response.getContentAsString().contains("402::"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectPublish(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        String channel = "/foo";
        AtomicInteger publishes = new AtomicInteger();
        new BroadcastChannelService(bayeux, channel, publishes);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        // Forge a bad sequence of messages to simulate concurrent arrival of disconnect and publish messages
        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "},{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "},{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertEquals(1, publishes.get());
        // The response to the publish must be that the session is unknown.
        Assertions.assertTrue(response.getContentAsString().contains("402::"));
    }

    public static class MetaSubscribeService extends AbstractService {
        private final AtomicBoolean serviced;

        public MetaSubscribeService(BayeuxServerImpl bayeux, AtomicBoolean serviced) {
            super(bayeux, "test");
            this.serviced = serviced;
            addService(Channel.META_SUBSCRIBE, "metaSubscribe");
        }

        public void metaSubscribe(ServerSession remote, ServerMessage message) {
            serviced.set(remote != null && remote.isHandshook());
        }
    }

    public static class BroadcastChannelService extends AbstractService {
        private final AtomicInteger publishes;

        public BroadcastChannelService(BayeuxServerImpl bayeux, String channel, AtomicInteger publishes) {
            super(bayeux, "test");
            this.publishes = publishes;
            addService(channel, "handle");
        }

        public void handle(ServerSession remote, ServerMessage message) {
            publishes.incrementAndGet();
        }
    }
}
