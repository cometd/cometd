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
package org.cometd.server;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetaConnectWithOtherMessagesTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testFirstMetaConnectWithOtherMessages(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channelName = "/test/multi";
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "},{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        JettyJSONContextClient parser = new JettyJSONContextClient();
        Message.Mutable[] messages = parser.parse(response.getContentAsString());

        Assertions.assertEquals(2, messages.length);

        Message.Mutable connectReply = messages[0];
        Assertions.assertEquals(Channel.META_CONNECT, connectReply.getChannel());

        Message.Mutable subscribeReply = messages[1];
        Assertions.assertEquals(Channel.META_SUBSCRIBE, subscribeReply.getChannel());

        ServerChannel channel = bayeux.getChannel(channelName);
        // Cannot be null since it has a subscriber.
        Assertions.assertNotNull(channel);
        Assertions.assertEquals(1, channel.getSubscribers().size());
    }
}
