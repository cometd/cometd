/*
 * Copyright (c) 2008-2014 the original author or authors.
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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BadJSONTest extends AbstractBayeuxClientServerTest
{
    @Before
    public void prepare() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testBadJSON() throws Exception
    {
        bayeux.setTransports(new JSONTransport(bayeux)
        {
            {
                init();
            }

            @Override
            protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws ServletException, IOException
            {
                // Suppress logging during tests
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
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

        ContentExchange connect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        Assert.assertEquals(200, connect.getResponseStatus());

        // Forge a bad JSON message
        ContentExchange badConnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"");
                //"}]"); Bad JSON, missing this line
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(badConnect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, badConnect.waitForDone());
        Assert.assertEquals(400, badConnect.getResponseStatus());
    }

    @Test
    public void testValidation() throws Exception
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

        ContentExchange connect = newBayeuxExchange("[{" +
                "\"id\": \"<script>alert();</script>\"," +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        Assert.assertEquals(500, connect.getResponseStatus());

        ContentExchange subscribe = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"/bar<script>\"" +
                "}]");
        subscribe.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(subscribe);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        Assert.assertEquals(500, subscribe.getResponseStatus());

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"/foo<>\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": \"{}\"" +
                "}]");
        publish.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(publish);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        Assert.assertEquals(500, publish.getResponseStatus());

        ContentExchange unsubscribe = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"/bar<script>\"" +
                "}]");
        unsubscribe.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(unsubscribe);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, unsubscribe.waitForDone());
        Assert.assertEquals(500, unsubscribe.getResponseStatus());
    }
}
