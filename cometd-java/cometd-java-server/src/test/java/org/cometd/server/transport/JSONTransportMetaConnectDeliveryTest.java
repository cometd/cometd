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

package org.cometd.server.transport;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class JSONTransportMetaConnectDeliveryTest extends AbstractBayeuxClientServerTest
{
    @Override
    protected void customizeOptions(Map<String, String> options)
    {
        options.put("long-polling.json.metaConnectDeliverOnly", "true");
    }

    @Test
    public void testJSONTransportMetaConnectDelivery() throws Exception
    {
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

        String channel = "/foo";

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // Expect only the meta response to the publish
        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Assert.assertEquals(1, messages.length);

        connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // Expect meta response to the connect plus the published message
        messages = jsonContext.parse(response.contentAsString());
        Assert.assertEquals(2, messages.length);
    }
}
