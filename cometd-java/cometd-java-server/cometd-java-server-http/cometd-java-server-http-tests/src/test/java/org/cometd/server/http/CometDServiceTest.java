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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDServiceTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testRemoveService(Transport transport) throws Exception {
        startServer(transport, null);

        String channel1 = "/foo";
        String channel2 = "/bar";
        AtomicReference<CountDownLatch> publishLatch1 = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> publishLatch2 = new AtomicReference<>(new CountDownLatch(1));
        AbstractService service = new OneTwoService(bayeux, channel1, channel2, publishLatch1, publishLatch2);

        Request handshake = newBayeuxRequest("[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish1 = newBayeuxRequest("[{" +
                                            "\"channel\": \"" + channel1 + "\"," +
                                            "\"clientId\": \"" + clientId + "\"," +
                                            "\"data\": {}" +
                                            "}]");
        response = publish1.send();
        Assertions.assertTrue(publishLatch1.get().await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(200, response.getStatus());

        Request publish2 = newBayeuxRequest("[{" +
                                            "\"channel\": \"" + channel2 + "\"," +
                                            "\"clientId\": \"" + clientId + "\"," +
                                            "\"data\": {}" +
                                            "}]");
        response = publish2.send();
        Assertions.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(200, response.getStatus());

        service.removeService(channel1, "one");
        publishLatch1.set(new CountDownLatch(1));
        publishLatch2.set(new CountDownLatch(1));

        publish1 = newBayeuxRequest("[{" +
                                    "\"channel\": \"" + channel1 + "\"," +
                                    "\"clientId\": \"" + clientId + "\"," +
                                    "\"data\": {}" +
                                    "}]");
        response = publish1.send();
        Assertions.assertFalse(publishLatch1.get().await(1, TimeUnit.SECONDS));
        Assertions.assertEquals(200, response.getStatus());

        publish2 = newBayeuxRequest("[{" +
                                    "\"channel\": \"" + channel2 + "\"," +
                                    "\"clientId\": \"" + clientId + "\"," +
                                    "\"data\": {}" +
                                    "}]");
        response = publish2.send();
        Assertions.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(200, response.getStatus());

        service.removeService(channel2);
        publishLatch2.set(new CountDownLatch(1));

        publish2 = newBayeuxRequest("[{" +
                                    "\"channel\": \"" + channel2 + "\"," +
                                    "\"clientId\": \"" + clientId + "\"," +
                                    "\"data\": {}" +
                                    "}]");
        response = publish2.send();
        Assertions.assertFalse(publishLatch2.get().await(1, TimeUnit.SECONDS));
        Assertions.assertEquals(200, response.getStatus());

        Request disconnect = newBayeuxRequest("[{" +
                                              "\"channel\": \"/meta/disconnect\"," +
                                              "\"clientId\": \"" + clientId + "\"" +
                                              "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
    }

    public static class OneTwoService extends AbstractService {
        private final AtomicReference<CountDownLatch> publishLatch1;
        private final AtomicReference<CountDownLatch> publishLatch2;

        public OneTwoService(BayeuxServerImpl bayeux, String channel1, String channel2, AtomicReference<CountDownLatch> publishLatch1, AtomicReference<CountDownLatch> publishLatch2) {
            super(bayeux, "test_remove");
            this.publishLatch1 = publishLatch1;
            this.publishLatch2 = publishLatch2;
            addService(channel1, "one");
            addService(channel2, "two");
        }

        public void one(ServerSession remote, ServerMessage message) {
            publishLatch1.get().countDown();
        }

        public void two(ServerSession remote, ServerMessage message) {
            publishLatch2.get().countDown();
        }
    }
}
