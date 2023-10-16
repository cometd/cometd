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
package org.cometd.javascript;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDConnectFailureTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectFailure(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new DeleteMetaConnectExtension());

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const handshakeLatch = new Latch(1);
                const connectLatch = new Latch(2);
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                cometd.addListener('/meta/connect', message => {
                    if (message.successful === false) {
                        connectLatch.countDown();
                    }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        int backoff = ((Number)evaluateScript("cometd.getBackoffPeriod();")).intValue();
        int backoffIncrement = ((Number)evaluateScript("cometd.getBackoffIncrement();")).intValue();
        Assertions.assertEquals(0, backoff);
        Assertions.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");

        // Time = 0.
        // First connect after handshake will fail,
        // will be retried after a backoff.
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(2L * backoffIncrement));

        // Time = 1.
        // Waits for the backoff to happen.
        Thread.sleep(backoffIncrement / 2);
        // Time = 1.5.
        backoff = ((Number)evaluateScript("cometd.getBackoffPeriod();")).intValue();
        // The backoff period is always the backoff that will be waited on the *next* failure.
        Assertions.assertEquals(2 * backoffIncrement, backoff);

        connectLatch.reset(1);
        Assertions.assertTrue(connectLatch.await(2L * backoffIncrement));

        // Time = 3.
        // Another failure, backoff will be increased to 3 * backoffIncrement.
        // Waits for the backoff to happen.
        Thread.sleep(backoffIncrement / 2);
        // Time = 3.5.
        backoff = ((Number)evaluateScript("cometd.getBackoffPeriod();")).intValue();
        Assertions.assertEquals(3 * backoffIncrement, backoff);

        connectLatch.reset(1);
        Assertions.assertTrue(connectLatch.await(3L * backoffIncrement));

        // Disconnect so that /meta/connect is not performed anymore.
        disconnect();

        // Be sure the /meta/connect is not retried anymore.
        connectLatch.reset(1);
        Assertions.assertFalse(connectLatch.await(4L * backoffIncrement));
    }

    private static class DeleteMetaConnectExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return !Channel.META_CONNECT.equals(message.getChannel());
        }
    }
}
