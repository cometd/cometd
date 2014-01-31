/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.websocket.server;

import java.net.HttpCookie;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Test;

public class BayeuxContextTest extends ClientServerWebSocketTest
{
    public BayeuxContextTest(String implementation)
    {
        super(implementation);
    }

    @Test
    public void testRequestHeaderIsCaseInsensitive() throws Exception
    {
        prepareAndStart(null);

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                BayeuxContext context = bayeux.getContext();
                Assert.assertEquals(context.getHeader("Host"), context.getHeader("HOST"));
                Assert.assertEquals(context.getHeader("Host"), context.getHeaderValues("HOST").get(0));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testCookiesSentToServer() throws Exception
    {
        prepareAndStart(null);

        final String cookieName = "name";
        final String cookieValue = "value";
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                BayeuxContext context = bayeux.getContext();
                Assert.assertEquals(cookieValue, context.getCookie(cookieName));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.putCookie(new HttpCookie(cookieName, cookieValue));
        client.handshake();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
