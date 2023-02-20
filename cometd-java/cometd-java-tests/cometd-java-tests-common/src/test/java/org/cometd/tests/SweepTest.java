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
package org.cometd.tests;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SweepTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMaxProcessingPostponesSweeping(Transport transport) throws Exception {
        Map<String, String> serverOptions = serverOptions(transport);
        int sweep = 500;
        serverOptions.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweep));
        int timeout = 1000;
        serverOptions.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        int maxProcessing = 4000;
        serverOptions.put(AbstractServerTransport.MAX_PROCESSING_OPTION, String.valueOf(maxProcessing));
        start(transport, serverOptions);

        BayeuxClient client = newBayeuxClient(transport);
        CountDownLatch suspendLatch = new CountDownLatch(1);
        CountDownLatch removeLatch = new CountDownLatch(1);
        client.handshake(r -> {
            ServerSession session = bayeux.getSession(r.getClientId());
            session.addListener(new ServerSession.HeartBeatListener() {
                @Override
                public void onSuspended(ServerSession session, ServerMessage message, long timeout) {
                    suspendLatch.countDown();
                }

                @Override
                public void onResumed(ServerSession session, ServerMessage message, boolean timeout) {
                    // Delay the resumption, the session must not be swept.
                    sleep(maxProcessing / 2);
                }
            });
            session.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());
        });

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(suspendLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(removeLatch.await(maxProcessing, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    private void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            throw new RuntimeException(x);
        }
    }
}
