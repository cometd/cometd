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

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConcurrentHandshakeFailureSubscribePublishTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrentHandshakeFailureAndSubscribe(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        // A bad sequence of messages results in the server rejecting them.
        String channelName = "/foo";
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(500, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrentHandshakeFailureAndPublish(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        // A bad sequence of messages results in the server rejecting them.
        String channelName = "/foo";
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"" + channelName + "\"," +
                "\"data\": {}" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(500, response.getStatus());
    }
}
