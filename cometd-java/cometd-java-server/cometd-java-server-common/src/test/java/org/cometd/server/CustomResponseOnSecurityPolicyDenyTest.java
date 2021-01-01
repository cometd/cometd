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
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CustomResponseOnSecurityPolicyDenyTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testCanHandshakeDenies(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String, Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        checkResponse(response, Message.RECONNECT_HANDSHAKE_VALUE);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCanCreateDenies(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message) {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String, Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });


        checkResponse(publish(), Message.RECONNECT_NONE_VALUE);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCanPublishDenies(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String, Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        checkResponse(publish(), Message.RECONNECT_NONE_VALUE);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCanSubscribeDenies(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                ServerMessage.Mutable reply = message.getAssociated();
                Map<String, Object> advice = reply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                Map<String, Object> ext = reply.getExt(true);
                Map<String, Object> extra = new HashMap<>();
                ext.put("com.acme", extra);
                extra.put("failure", "test");
                return false;
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        checkResponse(response, Message.RECONNECT_NONE_VALUE);
    }

    private ContentResponse publish() throws Exception {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"/test\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        return response;
    }

    private void checkResponse(ContentResponse reply, String reconnectAdvice) throws ParseException {
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] responses = jsonContext.parse(reply.getContentAsString());
        Assertions.assertEquals(1, responses.length);
        Message response = responses[0];
        Map<String, Object> advice = response.getAdvice();
        Assertions.assertNotNull(advice);
        Assertions.assertEquals(reconnectAdvice, advice.get(Message.RECONNECT_FIELD));
        Map<String, Object> ext = response.getExt();
        Assertions.assertNotNull(ext);
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>)ext.get("com.acme");
        Assertions.assertNotNull(extra);
        Assertions.assertEquals("test", extra.get("failure"));
    }
}
