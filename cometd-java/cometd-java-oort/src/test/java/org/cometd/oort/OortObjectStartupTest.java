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
package org.cometd.oort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortObjectStartupTest extends OortTest {
    private final List<OortObject<String>> oortObjects = new ArrayList<>();

    @AfterEach
    public void dispose() throws Exception {
        for (int i = oortObjects.size() - 1; i >= 0; --i) {
            oortObjects.get(i).stop();
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOortObjectStartup(String serverTransport) throws Exception {
        int nodes = 4;
        int edges = nodes * (nodes - 1);
        CountDownLatch joinLatch = new CountDownLatch(edges);
        Oort.CometListener joinListener = new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinLatch.countDown();
            }
        };
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(1024 * 1024));
        for (int i = 0; i < nodes; i++) {
            Oort oort = startOort(startServer(serverTransport, 0, options));
            oort.addCometListener(joinListener);
        }
        Oort oort1 = oorts.get(0);
        for (int i = 1; i < oorts.size(); i++) {
            Oort oort = oorts.get(i);
            OortComet oortComet1X = oort1.observeComet(oort.getURL());
            Assertions.assertTrue(oortComet1X.waitFor(5000, BayeuxClient.State.CONNECTED));
            OortComet oortCometX1 = oort.findComet(oort1.getURL());
            Assertions.assertTrue(oortCometX1.waitFor(5000, BayeuxClient.State.CONNECTED));
        }
        Assertions.assertTrue(joinLatch.await(nodes * 2, TimeUnit.SECONDS));
        Thread.sleep(1000);

        for (Oort oort : oorts) {
            oort.getBayeuxServer().addListener(new BayeuxServer.SubscriptionListener() {
                @Override
                public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
                    if (channel.getId().startsWith("/oort/objects/")) {
                        logger.info("{} subscription from {}", oort.getURL(), session);
                    }
                }
            });
        }

        CountDownLatch initialLatch = new CountDownLatch(edges);
        String name = "test_startup";
        for (int i = 0; i < oorts.size(); i++) {
            Oort oort = oorts.get(i);
            OortObject<String> oortObject = new OortObject<>(oort, name, OortObjectFactories.forString(i + "_default"));
            oortObject.addListener(new OortObject.Listener<>() {
                @Override
                public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                    if (oldInfo == null) {
                        logger.info("{} got part from {}", oort.getURL(), newInfo.getOortURL());
                        initialLatch.countDown();
                    }
                }
            });
            oortObjects.add(oortObject);
            oortObject.start();
            Thread.sleep(1000);
        }
        Assertions.assertTrue(initialLatch.await(nodes * 2, TimeUnit.SECONDS));
    }
}
