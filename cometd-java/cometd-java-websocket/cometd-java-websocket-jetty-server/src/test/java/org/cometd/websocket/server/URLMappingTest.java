/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.websocket.server;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Test;

public class URLMappingTest extends ClientServerWebSocketTest {
    public URLMappingTest(String wsTransportType) {
        super(wsTransportType);
    }

    @Test
    public void testURLMappingNoGlobbing() throws Exception {
        testURLMapping("/cometd");
    }

    @Test
    public void testRootURLMappingNoGlobbing() throws Exception {
        testURLMapping("/");
    }

    @Test
    public void testURLMappingWithGlobbing() throws Exception {
        testURLMapping("/cometd/*");
    }

    @Test
    public void testRootURLMappingWithGlobbing() throws Exception {
        testURLMapping("/*");
    }

    private void testURLMapping(String urlMapping) throws Exception {
        prepareAndStart(urlMapping, null);

        final BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for connect to establish
        Thread.sleep(1000);

        ClientTransport clientTransport = client.getTransport();
        Assert.assertEquals("websocket", clientTransport.getName());

        disconnectBayeuxClient(client);
    }
}
