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

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Assert;
import org.junit.Test;

public class ServerRedeployTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testServerRedeploy() throws Exception
    {
        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"advice\": { \"timeout\": 0 }" +
                "}]");
        response = connect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        Future<ContentResponse> futureResponse = connect.send();

        // Wait for the connect to arrive to the server
        Thread.sleep(1000);

        // Stop the context; this is the first half of a redeploy
        context.stop();

        // Expect the connect to be back with an exception
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.REQUEST_TIMEOUT_408, response.getStatus());
    }
}
