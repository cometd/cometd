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
package org.cometd.server.websocket;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class URLMappingTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testURLMappingNoGlobbing(String wsType) throws Exception {
        testURLMapping(wsType, "/cometd");
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testRootURLMappingNoGlobbing(String wsType) throws Exception {
        testURLMapping(wsType, "/");
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testURLMappingWithGlobbing(String wsType) throws Exception {
        testURLMapping(wsType, "/cometd/*");
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testRootURLMappingWithGlobbing(String wsType) throws Exception {
        testURLMapping(wsType, "/*");
    }

    private void testURLMapping(String wsType, String urlMapping) throws Exception {
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
}
