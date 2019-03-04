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
package org.cometd.server.filter;

import java.util.Collections;

import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class JSONDataFilterTest extends AbstractBayeuxClientServerTest {
    public JSONDataFilterTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testImmutableData() throws Exception {
        startServer(null);

        final String filtered = "/filtered";
        bayeux.createChannelIfAbsent(filtered, new ConfigurableServerChannel.Initializer() {
            @Override
            public void configureChannel(ConfigurableServerChannel channel) {
                channel.addListener(new DataFilterMessageListener(new NoScriptsFilter()));
            }
        });

        String unfiltered = "/service/unfiltered";
        bayeux.createChannelIfAbsent(unfiltered).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession session, ServerChannel channel, ServerMessage.Mutable message) {
                bayeux.getChannel(filtered).publish(session, Collections.unmodifiableMap(message.getDataAsMap()));
                return true;
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
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

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + filtered + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        String script = "<script>alert()</script>";
        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + unfiltered + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {" +
                "    \"message\": \"" + script + "\"" +
                "}" +
                "}]");
        response = publish.send();
        Assert.assertEquals(200, response.getStatus());

        String json = response.getContentAsString();
        String expected = script.replaceAll("script", "span");
        Assert.assertTrue(json.contains(expected));
    }
}
