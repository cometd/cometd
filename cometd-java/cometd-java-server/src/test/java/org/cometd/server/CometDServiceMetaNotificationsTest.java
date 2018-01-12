/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDServiceMetaNotificationsTest extends AbstractBayeuxClientServerTest {
    public CometDServiceMetaNotificationsTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testMetaNotifications() throws Exception {
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        new MetaChannelsService(bayeux, handshakeLatch, connectLatch, subscribeLatch, unsubscribeLatch, disconnectLatch);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        Request unsubscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = unsubscribe.send();
        Assert.assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());
    }

    public static class MetaChannelsService extends AbstractService {
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch connectLatch;
        private final CountDownLatch subscribeLatch;
        private final CountDownLatch unsubscribeLatch;
        private final CountDownLatch disconnectLatch;

        public MetaChannelsService(BayeuxServerImpl bayeux, CountDownLatch handshakeLatch, CountDownLatch connectLatch, CountDownLatch subscribeLatch, CountDownLatch unsubscribeLatch, CountDownLatch disconnectLatch) {
            super(bayeux, "test");
            this.handshakeLatch = handshakeLatch;
            this.connectLatch = connectLatch;
            this.subscribeLatch = subscribeLatch;
            this.unsubscribeLatch = unsubscribeLatch;
            this.disconnectLatch = disconnectLatch;
            addService(Channel.META_HANDSHAKE, "metaHandshake");
            addService(Channel.META_CONNECT, "metaConnect");
            addService(Channel.META_SUBSCRIBE, "metaSubscribe");
            addService(Channel.META_UNSUBSCRIBE, "metaUnsubscribe");
            addService(Channel.META_DISCONNECT, "metaDisconnect");
        }

        public void metaHandshake(ServerSession remote, ServerMessage message) {
            handshakeLatch.countDown();
        }

        public void metaConnect(ServerSession remote, ServerMessage message) {
            connectLatch.countDown();
        }

        public void metaSubscribe(ServerSession remote, ServerMessage message) {
            subscribeLatch.countDown();
        }

        public void metaUnsubscribe(ServerSession remote, ServerMessage message) {
            unsubscribeLatch.countDown();
        }

        public void metaDisconnect(ServerSession remote, ServerMessage message) {
            disconnectLatch.countDown();
        }
    }
}
