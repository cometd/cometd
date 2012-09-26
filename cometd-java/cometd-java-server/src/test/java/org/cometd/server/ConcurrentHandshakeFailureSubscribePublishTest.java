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

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class ConcurrentHandshakeFailureSubscribePublishTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testConcurrentHandshakeFailureAndSubscribe() throws Exception
    {
        bayeux.setSecurityPolicy(new Policy());

        final AtomicBoolean subscribe = new AtomicBoolean();
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
            }

            public void metaSubscribe(ServerSession remote, Message message)
            {
                subscribe.set(true);
            }
        };

        // A bad sequence of messages that clients should prevent
        // (by not allowing a subscribe until the handshake is completed)
        // yet the server must behave properly
        String channelName = "/foo";
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Assert.assertEquals(2, messages.length);
        Message handshakeResponse = messages[0];
        Assert.assertFalse(handshakeResponse.isSuccessful());
        String handshakeError = (String)handshakeResponse.get("error");
        Assert.assertNotNull(handshakeError);
        Assert.assertTrue(handshakeError.contains("403"));
        Map<String, Object> advice = handshakeResponse.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(Message.RECONNECT_NONE_VALUE, advice.get("reconnect"));
        Message subscribeResponse = messages[1];
        Assert.assertFalse(subscribeResponse.isSuccessful());
        String subscribeError = (String)subscribeResponse.get("error");
        Assert.assertNotNull(subscribeError);
        Assert.assertTrue(subscribeError.contains("402"));
        Assert.assertNull(subscribeResponse.getAdvice());

        Assert.assertFalse(subscribe.get());
    }

    @Test
    public void testConcurrentHandshakeFailureAndPublish() throws Exception
    {
        bayeux.setSecurityPolicy(new Policy());

        final String channelName = "/foo";
        final AtomicBoolean publish = new AtomicBoolean();
        new AbstractService(bayeux, "test")
        {
            {
                addService(channelName, "process");
            }

            public void process(ServerSession remote, Message message)
            {
                publish.set(true);
            }
        };

        // A bad sequence of messages that clients should prevent
        // (by not allowing a publish until the handshake is completed)
        // yet the server must behave properly
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"" + channelName + "\"," +
                "\"data\": {}" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Assert.assertEquals(2, messages.length);
        Message handshakeResponse = messages[0];
        Assert.assertFalse(handshakeResponse.isSuccessful());
        String handshakeError = (String)handshakeResponse.get("error");
        Assert.assertNotNull(handshakeError);
        Assert.assertTrue(handshakeError.contains("403"));
        Map<String, Object> advice = handshakeResponse.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(Message.RECONNECT_NONE_VALUE, advice.get("reconnect"));
        Message publishResponse = messages[1];
        Assert.assertFalse(publishResponse.isSuccessful());
        String publishError = (String)publishResponse.get("error");
        Assert.assertNotNull(publishError);
        Assert.assertTrue(publishError.contains("402"));
        Assert.assertNull(publishResponse.getAdvice());

        Assert.assertFalse(publish.get());
    }

    private class Policy extends DefaultSecurityPolicy
    {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            if (session.isLocalSession())
                return true;
            Map<String,Object> ext = message.getExt();
            return ext != null && ext.get("authn") != null;
        }
    }
}
