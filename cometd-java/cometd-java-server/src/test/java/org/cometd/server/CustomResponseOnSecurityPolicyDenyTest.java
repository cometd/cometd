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

import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CustomResponseOnSecurityPolicyDenyTest extends AbstractBayeuxClientServerTest
{
    @Before
    public void prepare() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testCanHandshakeDenies() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String,Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<String, Object>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        checkResponse(handshake, Message.RECONNECT_HANDSHAKE_VALUE);
    }

    @Test
    public void testCanCreateDenies() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message)
            {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String,Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<String, Object>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });


        checkResponse(publish(), Message.RECONNECT_NONE_VALUE);
    }

    @Test
    public void testCanPublishDenies() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
            {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String, Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<String, Object>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        checkResponse(publish(), Message.RECONNECT_NONE_VALUE);
    }

    @Test
    public void testCanSubscribeDenies() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
            {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String,Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<String, Object>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange subscribe = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        subscribe.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(subscribe);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        Assert.assertEquals(200, subscribe.getResponseStatus());

        checkResponse(subscribe, Message.RECONNECT_NONE_VALUE);
    }

    private ContentExchange publish() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        publish.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(publish);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        Assert.assertEquals(200, publish.getResponseStatus());

        return publish;
    }

    private void checkResponse(ContentExchange reply, String reconnectAdvice) throws ParseException, UnsupportedEncodingException
    {
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] responses = jsonContext.parse(reply.getResponseContent());
        Assert.assertEquals(1, responses.length);
        Message response = responses[0];
        Map<String, Object> advice = response.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(reconnectAdvice, advice.get(Message.RECONNECT_FIELD));
        Map<String, Object> ext = response.getExt();
        Assert.assertNotNull(ext);
        Map<String, Object> extra = (Map<String, Object>)ext.get("com.acme");
        Assert.assertNotNull(extra);
        Assert.assertEquals("test", extra.get("failure"));
    }
}
