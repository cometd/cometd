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

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpCookie;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SessionHijackingTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionHijackingAllowed(Transport transport) throws Exception {
        Map<String, String> settings = new HashMap<>();
        settings.put(AbstractHttpTransport.TRUST_CLIENT_SESSION_OPTION, String.valueOf(true));
        startServer(transport, settings);

        // Message should succeed.
        List<Message.Mutable> messages = testSessionHijacking();
        Assertions.assertEquals(1, messages.size());
        Message message = messages.get(0);
        Assertions.assertTrue(message.isSuccessful());
    }

    private List<Message.Mutable> testSessionHijacking() throws UnsupportedEncodingException, InterruptedException, TimeoutException, ExecutionException, java.text.ParseException {
        String cookieName = "BAYEUX_BROWSER";

        Request handshake1 = newBayeuxRequest("[{" +
                                              "\"channel\": \"/meta/handshake\"," +
                                              "\"version\": \"1.0\"," +
                                              "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                              "}]");
        ContentResponse response1 = handshake1.send();
        Assertions.assertEquals(200, response1.getStatus());

        String cookie1 = extractCookie(cookieName);
        // Reset cookies to control what cookies this test sends.
        httpClient.getHttpCookieStore().clear();

        Request handshake2 = newBayeuxRequest("[{" +
                                              "\"channel\": \"/meta/handshake\"," +
                                              "\"version\": \"1.0\"," +
                                              "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                              "}]");
        ContentResponse response2 = handshake2.send();
        Assertions.assertEquals(200, response2.getStatus());

        String clientId2 = extractClientId(response2);
        // Reset cookies to control what cookies this test sends.
        httpClient.getHttpCookieStore().clear();

        // Client1 tries to impersonate Client2.
        Request publish1 = newBayeuxRequest("[{" +
                                            "\"channel\": \"/session_mismatch\"," +
                                            "\"data\": \"publish_data\"," +
                                            "\"clientId\": \"" + clientId2 + "\"" +
                                            "}]");
        publish1.cookie(HttpCookie.from(cookieName, cookie1));
        response1 = publish1.send();
        Assertions.assertEquals(200, response1.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        return parser.parse(response1.getContentAsString());
    }
}
