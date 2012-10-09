/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CookiesTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testCookieSentOnHandshakeResponse() throws Exception
    {
        final AtomicReference<String> browserCookie = new AtomicReference<>();
        final BayeuxClient client = newBayeuxClient();
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                browserCookie.set(client.getCookie("BAYEUX_BROWSER"));
            }
        });
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        assertNotNull(browserCookie.get());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCookiesExpiration() throws Exception
    {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        client.setCookie("foo", "bar", 1);
        assertNotNull(client.getCookie("foo"));

        // Allow cookie to expire
        Thread.sleep(1500);

        assertNull(client.getCookie("foo"));

        client.setCookie("foo", "bar");
        assertNotNull(client.getCookie("foo"));

        Thread.sleep(1500);

        assertNotNull(client.getCookie("foo"));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMultipleCookies() throws Exception
    {
        final String cookie1 = "cookie1";
        final String cookie2 = "cookie2";
        final String channelName = "/channel";
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final CountDownLatch publishLatch = new CountDownLatch(1);
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_HANDSHAKE, "metaHandshake");
                addService(Channel.META_CONNECT, "metaConnect");
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
                addService(Channel.META_UNSUBSCRIBE, "metaUnsubscribe");
                addService(channelName, "process");
            }

            public void metaHandshake(ServerSession session, ServerMessage message)
            {
                BayeuxContext context = getBayeux().getContext();
                String value1 = context.getCookie(cookie1);
                String value2 = context.getCookie(cookie2);
                if (value1 != null && value2 != null)
                    handshakeLatch.countDown();
            }

            public void metaConnect(ServerSession session, ServerMessage message)
            {
                BayeuxContext context = getBayeux().getContext();
                String value1 = context.getCookie(cookie1);
                String value2 = context.getCookie(cookie2);
                String value3 = context.getCookie("BAYEUX_BROWSER");
                if (value1 != null && value2 != null && value3 != null)
                    connectLatch.countDown();
            }

            public void metaSubscribe(ServerSession session, ServerMessage message)
            {
                BayeuxContext context = getBayeux().getContext();
                String value1 = context.getCookie(cookie1);
                String value2 = context.getCookie(cookie2);
                String value3 = context.getCookie("BAYEUX_BROWSER");
                if (value1 != null && value2 != null && value3 != null)
                    subscribeLatch.countDown();
            }

            public void metaUnsubscribe(ServerSession session, ServerMessage message)
            {
                BayeuxContext context = getBayeux().getContext();
                String value1 = context.getCookie(cookie1);
                String value2 = context.getCookie(cookie2);
                String value3 = context.getCookie("BAYEUX_BROWSER");
                if (value1 != null && value2 != null && value3 != null)
                    unsubscribeLatch.countDown();
            }

            public void process(ServerSession session, ServerMessage message)
            {
                BayeuxContext context = getBayeux().getContext();
                String value1 = context.getCookie(cookie1);
                String value2 = context.getCookie(cookie2);
                String value3 = context.getCookie("BAYEUX_BROWSER");
                if (value1 != null && value2 != null && value3 != null)
                    publishLatch.countDown();
            }
        };

        BayeuxClient client = newBayeuxClient();

        client.setCookie(cookie1, "value1");
        client.setCookie(cookie2, "value2");

        client.handshake();

        assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        ClientSessionChannel channel = client.getChannel(channelName);
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });
        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        channel.publish(new HashMap());
        assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        channel.unsubscribe();
        assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
