/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.net.HttpCookie;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.transport.AbstractHttpTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class SessionHijackingTest extends AbstractBayeuxClientServerTest {
    public SessionHijackingTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testSessionHijackingAllowed() throws Exception {
        Map<String, String> settings = new HashMap<>();
        settings.put(AbstractHttpTransport.TRUST_CLIENT_SESSION, String.valueOf(true));
        startServer(settings);

        // Message should succeed.
        Message.Mutable[] messages = testSessionHijacking();
        Assert.assertEquals(1, messages.length);
        Message message = messages[0];
        Assert.assertTrue(message.isSuccessful());
    }

    private Message.Mutable[] testSessionHijacking() throws UnsupportedEncodingException, InterruptedException, TimeoutException, ExecutionException, java.text.ParseException {
        String cookieName = "BAYEUX_BROWSER";

        Request handshake1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response1 = handshake1.send();
        Assert.assertEquals(200, response1.getStatus());

        String cookie1 = extractCookie(cookieName);
        // Reset cookies to control what cookies this test sends.
        httpClient.getCookieStore().removeAll();

        Request handshake2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response2 = handshake2.send();
        Assert.assertEquals(200, response2.getStatus());

        String clientId2 = extractClientId(response2);
        // Reset cookies to control what cookies this test sends.
        httpClient.getCookieStore().removeAll();

        // Client1 tries to impersonate Client2.
        Request publish1 = newBayeuxRequest("[{" +
                "\"channel\": \"/session_mismatch\"," +
                "\"data\": \"publish_data\"," +
                "\"clientId\": \"" + clientId2 + "\"" +
                "}]");
        publish1.cookie(new HttpCookie(cookieName, cookie1));
        response1 = publish1.send();
        Assert.assertEquals(200, response1.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        return parser.parse(response1.getContentAsString());
    }
}
