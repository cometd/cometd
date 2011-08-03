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

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContext;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.junit.Assert;
import org.junit.Test;

public class HandshakeFailureCustomResponseTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testHandshakeFailureCustomResponse() throws Exception
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                ServerMessage.Mutable handshakeReply = message.getAssociated();
                Map<String,Object> advice = handshakeReply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                Map<String, Object> ext = handshakeReply.getExt(true);
                Map<String, Object> authentication = new HashMap<String, Object>();
                ext.put("com.acme.auth", authentication);
                authentication.put("failure", "test");
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

        JSONContext<Message.Mutable> jsonContext = new JettyJSONContext();
        Message.Mutable[] responses = jsonContext.parse(handshake.getResponseContent());
        Assert.assertEquals(1, responses.length);
        Message response = responses[0];
        Map<String, Object> advice = response.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(Message.RECONNECT_HANDSHAKE_VALUE, advice.get(Message.RECONNECT_FIELD));
        Map<String, Object> ext = response.getExt();
        Assert.assertNotNull(ext);
        Map<String, Object> authentication = (Map<String, Object>)ext.get("com.acme.auth");
        Assert.assertNotNull(authentication);
        Assert.assertEquals("test", authentication.get("failure"));
    }
}
