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
package org.cometd.server.http;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BayeuxContextTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testAddresses(Transport transport) throws Exception {
        startServer(transport, null);

        CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                Assertions.assertNotNull(message.getBayeuxContext().getLocalAddress());
                Assertions.assertNotNull(message.getBayeuxContext().getRemoteAddress());
                latch.countDown();
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestHeader(Transport transport) throws Exception {
        startServer(transport, null);

        String name = "test";
        String value1 = "foo";
        String value2 = "bar";
        CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                Assertions.assertEquals(value1, message.getBayeuxContext().getHeader(name));
                Assertions.assertEquals(List.of(value1, value2), message.getBayeuxContext().getHeaderValues(name));
                latch.countDown();
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        handshake.headers(headers -> headers.put(name, value1));
        handshake.headers(headers -> headers.add(name, value2));
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContextAttribute(Transport transport) throws Exception {
        startServer(transport, null);

        CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                Assertions.assertSame(bayeux, message.getBayeuxContext().getContextAttribute(BayeuxServer.ATTRIBUTE));
                latch.countDown();
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
