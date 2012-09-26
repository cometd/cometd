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

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class BadJSONTest extends AbstractBayeuxClientServerTest
{
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

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send().get(5, TimeUnit.SECONDS);

        Assert.assertEquals(200, response.status());

        // Forge a bad JSON message
        Request badConnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"");
                //"}]"); Bad JSON, missing this line
        response = badConnect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(400, response.status());
    }
}
