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
package org.cometd.tests;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetaConnectDeliverOnlyTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectDeliveryOnly(Transport transport) throws Exception {
        startServer(transport, serverOptions(transport));

        BayeuxClient client = newBayeuxClient(transport);

        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.setMetaConnectDeliveryOnly(true);
            }
        });

        String channelName = "/delivery";
        CountDownLatch latch = new CountDownLatch(2);
        client.handshake(hsReply -> {
            if (hsReply.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> {
                    latch.countDown();
                    if (latch.getCount() == 1) {
                        bayeux.getChannel(channelName).publish(null, "data2", Promise.noop());
                        Assertions.assertFalse(await(latch, Duration.ofSeconds(1)));
                    }
                });
            }
        });

        // Wait for the /meta/connect to be suspended.
        Thread.sleep(1000);

        // This server-side publish should wake up the /meta/connect.
        // In the message listener, we perform a second server-side
        // publish and the second message should not arrive to the
        // client yet because we deliver messages only via /meta/connect.
        bayeux.getChannel(channelName).publish(null, "data1", Promise.noop());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        client.disconnect();
        Assertions.assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    private boolean await(CountDownLatch latch, Duration duration) {
        try {
            return latch.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException x) {
            return false;
        }
    }
}
