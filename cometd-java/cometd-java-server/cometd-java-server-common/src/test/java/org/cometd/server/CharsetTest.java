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

import java.nio.charset.StandardCharsets;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CharsetTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMissingContentTypeWithLongPolling(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String data = new String(new byte[]{(byte)0xC3, (byte)0xA9}, StandardCharsets.UTF_8);
        String channelName = "/test_charset";
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                String messageData = (String)message.getData();
                Assertions.assertEquals(data, messageData);
                return true;
            }
        });

        Request publish = newBayeuxRequest("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\":\"" + data + "\"" +
                "}]");
        // In some cross domain configuration (for example IE9 using XDomainRequest),
        // the Content-Type header is not sent, and we must behave well even if it's missing
        publish.onResponseBegin(r -> publish.headers(headers -> headers.remove(HttpHeader.CONTENT_TYPE)));
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContentTypeWithISO_8859_7(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        // Greek encoding
        String encoding = "ISO-8859-7";
        // Lowercase greek letter alpha
        String data = new String(new byte[]{(byte)0xE1}, encoding);
        String channelName = "/test_charset";
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                String messageData = (String)message.getData();
                Assertions.assertEquals(data, messageData);
                return true;
            }
        });

        Request publish = httpClient.newRequest(cometdURL);
        configureBayeuxRequest(publish, "[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\":\"" + data + "\"" +
                "}]", encoding);
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
    }
}
