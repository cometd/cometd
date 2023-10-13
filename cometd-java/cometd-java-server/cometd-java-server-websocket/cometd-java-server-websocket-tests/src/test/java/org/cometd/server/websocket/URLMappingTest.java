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
package org.cometd.server.websocket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class URLMappingTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testURLMappingNoGlobbing(Transport wsType) throws Exception {
        testURLMapping(wsType, "/cometd");
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRootURLMappingNoGlobbing(Transport wsType) throws Exception {
        testURLMapping(wsType, "/");
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testURLMappingWithGlobbing(Transport wsType) throws Exception {
        testURLMapping(wsType, "/cometd/*");
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRootURLMappingWithGlobbing(Transport wsType) throws Exception {
        testURLMapping(wsType, "/*");
    }

    private void testURLMapping(Transport wsType, String urlMapping) throws Exception {
        prepareAndStart(wsType, urlMapping, null);

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for connect to establish
        Thread.sleep(1000);

        ClientTransport clientTransport = client.getTransport();
        Assertions.assertEquals("websocket", clientTransport.getName());

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMultipleURLMappings(Transport wsType) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractWebSocketTransport.COMETD_URL_MAPPING_OPTION, "/cometd/ws1,/cometd/ws2");
        prepareAndStart(wsType, "/cometd", options);

        BayeuxClient client1 = newBayeuxClient(wsType, "ws://localhost:%d/cometd/ws1".formatted(connector.getLocalPort()));
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client2 = newBayeuxClient(wsType, "ws://localhost:%d/cometd/ws2".formatted(connector.getLocalPort()));
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        String channelName = "/foobarbaz";
        CountDownLatch messageLatch = new CountDownLatch(4);
        CountDownLatch subscribeLatch = new CountDownLatch(2);
        client1.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        client2.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        client1.getChannel(channelName).publish("1");
        client2.getChannel(channelName).publish("2");

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        client1.disconnect(1000);
        client2.disconnect(1000);
    }
}
