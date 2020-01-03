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

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BadJSONTest extends AbstractBayeuxClientServerTest {
    public BadJSONTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testBadJSON() throws Exception {
        JSONTransport transport = new JSONTransport(bayeux) {
            @Override
            protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws IOException {
                // Suppress logging during tests
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                }
            }
        };
        transport.init();
        bayeux.setTransports(transport);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();

        Assert.assertEquals(200, response.getStatus());

        // Forge a bad JSON message
        Request badConnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"");
        //"}]"); Bad JSON, missing this line
        response = badConnect.send();
        Assert.assertEquals(400, response.getStatus());
    }

    @Test
    public void testValidation() throws Exception {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"id\": \"<script>alert();</script>\"," +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] replies = jsonContext.parse(response.getContentAsString());
        Message.Mutable reply = replies[0];
        Assert.assertFalse(reply.isSuccessful());

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"/bar<script>\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assert.assertFalse(reply.isSuccessful());

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"/foo<>\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": \"{}\"" +
                "}]");
        response = publish.send();
        Assert.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assert.assertFalse(reply.isSuccessful());

        Request unsubscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"/bar<script>\"" +
                "}]");
        response = unsubscribe.send();
        Assert.assertEquals(200, response.getStatus());
        replies = jsonContext.parse(response.getContentAsString());
        reply = replies[0];
        Assert.assertFalse(reply.isSuccessful());
    }
}
