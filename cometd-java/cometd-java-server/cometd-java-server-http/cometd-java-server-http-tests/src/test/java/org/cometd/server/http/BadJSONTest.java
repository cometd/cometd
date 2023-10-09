/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.http;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BadJSONTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testBadJSON(Transport transport) throws Exception {
        startServer(transport, null);

        Request handshake = newBayeuxRequest("""
                [{
                "channel": "/meta/handshake",
                "version": "1.0",
                "minimumVersion": "1.0",
                "supportedConnectionTypes": ["long-polling"]
                }]
                """);
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("""
                [{
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling"
                }]
                """.formatted(clientId));
        response = connect.send();

        Assertions.assertEquals(200, response.getStatus());

        // Forge a bad JSON message, missing the closing brackets.
        Request badConnect = newBayeuxRequest("""
                [{
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling"
                """.formatted(clientId));
        response = badConnect.send();
        Assertions.assertEquals(400, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testValidation(Transport transport) throws Exception {
        startServer(transport, null);

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                                           "\"id\": \"<script>alert();</script>\"," +
                                           "\"channel\": \"/meta/connect\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"connectionType\": \"long-polling\"" +
                                           "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] replies = jsonContext.parse(response.getContentAsString());
        Message.Mutable reply = replies[0];
        Assertions.assertFalse(reply.isSuccessful());

        Request subscribe = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/subscribe\"," +
                                             "\"clientId\": \"" + clientId + "\"," +
                                             "\"subscription\": \"/bar<script>\"" +
                                             "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assertions.assertFalse(reply.isSuccessful());

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"/foo<>\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": \"{}\"" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assertions.assertFalse(reply.isSuccessful());

        Request unsubscribe = newBayeuxRequest("[{" +
                                               "\"channel\": \"/meta/unsubscribe\"," +
                                               "\"clientId\": \"" + clientId + "\"," +
                                               "\"subscription\": \"/bar<script>\"" +
                                               "}]");
        response = unsubscribe.send();
        Assertions.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assertions.assertFalse(reply.isSuccessful());
    }
}
