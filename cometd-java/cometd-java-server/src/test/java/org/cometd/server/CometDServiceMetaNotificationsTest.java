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

package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class CometDServiceMetaNotificationsTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testMetaNotifications() throws Exception
    {
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_HANDSHAKE, "metaHandshake");
                addService(Channel.META_CONNECT, "metaConnect");
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
                addService(Channel.META_UNSUBSCRIBE, "metaUnsubscribe");
                addService(Channel.META_DISCONNECT, "metaDisconnect");
            }

            public void metaHandshake(ServerSession remote, Message message)
            {
                handshakeLatch.countDown();
            }

            public void metaConnect(ServerSession remote, Message message)
            {
                connectLatch.countDown();
            }

            public void metaSubscribe(ServerSession remote, Message message)
            {
                subscribeLatch.countDown();
            }

            public void metaUnsubscribe(ServerSession remote, Message message)
            {
                unsubscribeLatch.countDown();
            }

            public void metaDisconnect(ServerSession remote, Message message)
            {
                disconnectLatch.countDown();
            }
        };

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send().get(5, TimeUnit.SECONDS);

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        Request unsubscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = unsubscribe.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());
    }
}
