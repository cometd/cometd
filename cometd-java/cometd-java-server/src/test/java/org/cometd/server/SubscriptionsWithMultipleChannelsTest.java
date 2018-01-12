/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SubscriptionsWithMultipleChannelsTest extends AbstractBayeuxClientServerTest {
    public SubscriptionsWithMultipleChannelsTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testSubscribeWithMultipleChannels() throws Exception {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": [\"/foo\",\"/bar\"]" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Assert.assertTrue(message.isSuccessful());
        Object subscriptions = message.get(Message.SUBSCRIPTION_FIELD);
        Assert.assertTrue(subscriptions instanceof Object[]);
    }

    @Test
    public void testUnsubscribeWithMultipleChannels() throws Exception {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": [\"/foo\",\"/bar\"]" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        Request unsubscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": [\"/foo\",\"/bar\"]" +
                "}]");
        response = unsubscribe.send();
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assert.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Assert.assertTrue(message.isSuccessful());
        Object subscriptions = message.get(Message.SUBSCRIPTION_FIELD);
        Assert.assertTrue(subscriptions instanceof Object[]);
    }
}
