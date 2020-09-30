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

import java.net.HttpCookie;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractService;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CookiesTest extends ClientServerTest {
    @Before
    public void init() throws Exception {
        start(null);
    }

    @Test
    public void testCookieSentOnHandshakeResponse() {
        AtomicReference<HttpCookie> browserCookie = new AtomicReference<>();
        BayeuxClient client = newBayeuxClient();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> browserCookie.set(client.getCookie("BAYEUX_BROWSER")));
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        assertNotNull(browserCookie.get());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCookiesExpiration() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        long maxAge = 1;
        HttpCookie cookie = new HttpCookie("foo", "bar");
        cookie.setMaxAge(maxAge);
        client.putCookie(cookie);
        assertNotNull(client.getCookie(cookie.getName()));

        // Allow cookie to expire
        TimeUnit.SECONDS.sleep(maxAge * 2);

        assertNull(client.getCookie(cookie.getName()));

        cookie = new HttpCookie("foo", "bar");
        client.putCookie(cookie);
        assertNotNull(client.getCookie(cookie.getName()));

        TimeUnit.SECONDS.sleep(maxAge * 2);

        assertNotNull(client.getCookie(cookie.getName()));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMultipleCookies() throws Exception {
        String cookie1 = "cookie1";
        String cookie2 = "cookie2";
        String channelName = "/channel";
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        CountDownLatch publishLatch = new CountDownLatch(1);
        new CookieService(bayeux, channelName, cookie1, cookie2, handshakeLatch, connectLatch, subscribeLatch, unsubscribeLatch, publishLatch);

        BayeuxClient client = newBayeuxClient();

        client.putCookie(new HttpCookie(cookie1, "value1"));
        client.putCookie(new HttpCookie(cookie2, "value2"));

        client.handshake();

        assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel(channelName);
        channel.subscribe((c, m) -> {
        });
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        channel.publish(new HashMap<>());
        assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        channel.unsubscribe();
        assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    public static class CookieService extends AbstractService {
        private final String cookie1;
        private final String cookie2;
        private final CountDownLatch handshakeLatch;
        private final CountDownLatch connectLatch;
        private final CountDownLatch subscribeLatch;
        private final CountDownLatch unsubscribeLatch;
        private final CountDownLatch publishLatch;

        public CookieService(BayeuxServer bayeux, String channelName, String cookie1, String cookie2, CountDownLatch handshakeLatch, CountDownLatch connectLatch, CountDownLatch subscribeLatch, CountDownLatch unsubscribeLatch, CountDownLatch publishLatch) {
            super(bayeux, "test");
            this.cookie1 = cookie1;
            this.cookie2 = cookie2;
            this.handshakeLatch = handshakeLatch;
            this.connectLatch = connectLatch;
            this.subscribeLatch = subscribeLatch;
            this.unsubscribeLatch = unsubscribeLatch;
            this.publishLatch = publishLatch;
            addService(Channel.META_HANDSHAKE, "metaHandshake");
            addService(Channel.META_CONNECT, "metaConnect");
            addService(Channel.META_SUBSCRIBE, "metaSubscribe");
            addService(Channel.META_UNSUBSCRIBE, "metaUnsubscribe");
            addService(channelName, "process");
        }

        @SuppressWarnings("unused")
        public void metaHandshake(ServerSession session, ServerMessage message) {
            BayeuxContext context = message.getBayeuxContext();
            String value1 = context.getCookie(cookie1);
            String value2 = context.getCookie(cookie2);
            if (value1 != null && value2 != null) {
                handshakeLatch.countDown();
            }
        }

        @SuppressWarnings("unused")
        public void metaConnect(ServerSession session, ServerMessage message) {
            BayeuxContext context = message.getBayeuxContext();
            String value1 = context.getCookie(cookie1);
            String value2 = context.getCookie(cookie2);
            String value3 = context.getCookie("BAYEUX_BROWSER");
            if (value1 != null && value2 != null && value3 != null) {
                connectLatch.countDown();
            }
        }

        @SuppressWarnings("unused")
        public void metaSubscribe(ServerSession session, ServerMessage message) {
            BayeuxContext context = message.getBayeuxContext();
            String value1 = context.getCookie(cookie1);
            String value2 = context.getCookie(cookie2);
            String value3 = context.getCookie("BAYEUX_BROWSER");
            if (value1 != null && value2 != null && value3 != null) {
                subscribeLatch.countDown();
            }
        }

        @SuppressWarnings("unused")
        public void metaUnsubscribe(ServerSession session, ServerMessage message) {
            BayeuxContext context = message.getBayeuxContext();
            String value1 = context.getCookie(cookie1);
            String value2 = context.getCookie(cookie2);
            String value3 = context.getCookie("BAYEUX_BROWSER");
            if (value1 != null && value2 != null && value3 != null) {
                unsubscribeLatch.countDown();
            }
        }

        @SuppressWarnings("unused")
        public void process(ServerSession session, ServerMessage message) {
            BayeuxContext context = message.getBayeuxContext();
            String value1 = context.getCookie(cookie1);
            String value2 = context.getCookie(cookie2);
            String value3 = context.getCookie("BAYEUX_BROWSER");
            if (value1 != null && value2 != null && value3 != null) {
                publishLatch.countDown();
            }
        }
    }
}
