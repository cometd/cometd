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

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

public class ServerShutdownTest extends AbstractBayeuxClientServerTest
{
    public void testServerShutdown() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange connect = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"advice\": { \"timeout\": 0 }" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        assertEquals(200, connect.getResponseStatus());

        connect = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);

        // Wait for the connect to arrive to the server
        Thread.sleep(500);

        // Shutdown the server
        server.stop();
        server.join();

        // Expect the connect to be back with an exception
        assertEquals(HttpExchange.STATUS_EXCEPTED, connect.waitForDone());
    }
}
