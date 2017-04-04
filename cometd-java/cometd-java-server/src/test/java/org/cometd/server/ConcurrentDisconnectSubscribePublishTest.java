/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConcurrentDisconnectSubscribePublishTest extends AbstractBayeuxClientServerTest {
    public ConcurrentDisconnectSubscribePublishTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testDisconnectSubscribe() throws Exception {
        final AtomicBoolean subscribed = new AtomicBoolean(false);
        bayeux.addListener(new BayeuxServer.SubscriptionListener() {
            @Override
            public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            }

            @Override
            public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
                subscribed.set(true);
            }
        });

        final AtomicBoolean serviced = new AtomicBoolean(false);
        new MetaSubscribeService(bayeux, serviced);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());

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
        Assert.assertEquals(200, response.getStatus());

        Assert.assertFalse("not subscribed", subscribed.get());
        Assert.assertFalse("not serviced", serviced.get());
        // The response to the subscribe must be that the client is unknown
        Assert.assertTrue(response.getContentAsString().contains("402::"));
    }

    @Test
    public void testDisconnectPublish() throws Exception {
        final String channel = "/foo";
        final AtomicInteger publishes = new AtomicInteger();
        new BroadcastChannelService(bayeux, channel, publishes);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

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
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(1, publishes.get());
        // The response to the subscribe must be that the client is unknown
        Assert.assertTrue(response.getContentAsString().contains("402::"));
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
