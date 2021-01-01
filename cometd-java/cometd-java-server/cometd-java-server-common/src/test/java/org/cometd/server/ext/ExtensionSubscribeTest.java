/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.server.ext;

import java.util.ArrayList;
import java.util.List;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ExtensionSubscribeTest extends AbstractBayeuxClientServerTest {
    private final CountingExtension extension = new CountingExtension();

    @ParameterizedTest
    @MethodSource("transports")
    public void testExtension(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        bayeux.addExtension(extension);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertEquals(0, extension.rcvs.size());
        Assertions.assertEquals(1, extension.rcvMetas.size());
        Assertions.assertEquals(0, extension.sends.size());
        Assertions.assertEquals(1, extension.sendMetas.size());
    }

    private static class CountingExtension implements BayeuxServer.Extension {
        private final List<Message> rcvs = new ArrayList<>();
        private final List<Message> rcvMetas = new ArrayList<>();
        private final List<Message> sends = new ArrayList<>();
        private final List<Message> sendMetas = new ArrayList<>();

        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            rcvs.add(message);
            return true;
        }

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_SUBSCRIBE.equals(message.getChannel())) {
                rcvMetas.add(message);
            }
            return true;
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            sends.add(message);
            return true;
        }

        @Override
        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
            if (Channel.META_SUBSCRIBE.equals(message.getChannel())) {
                sendMetas.add(message);
            }
            return true;
        }
    }
}
