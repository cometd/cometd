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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class JSONTransportMetaConnectDeliveryTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testJSONTransportMetaConnectDelivery(Transport transport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("long-polling.json.metaConnectDeliverOnly", "true");
        startServer(transport, options);

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
                                           "\"channel\": \"/meta/connect\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"connectionType\": \"long-polling\"" +
                                           "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        String channel = "/foo";

        Request subscribe = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/subscribe\"," +
                                             "\"clientId\": \"" + clientId + "\"," +
                                             "\"subscription\": \"" + channel + "\"" +
                                             "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        Request publish = newBayeuxRequest("[{" +
                                           "\"channel\": \"" + channel + "\"," +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"data\": {}" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        // Expect only the meta response to the publish
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        List<Message.Mutable> messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.size());

        connect = newBayeuxRequest("[{" +
                                   "\"channel\": \"/meta/connect\"," +
                                   "\"clientId\": \"" + clientId + "\"," +
                                   "\"connectionType\": \"long-polling\"" +
                                   "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        // Expect meta response to the connect plus the published message
        messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(2, messages.size());
    }
}
