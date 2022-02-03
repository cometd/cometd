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

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "    if (message.successful === false) {" +
                "        connectLatch.countDown();" +
                "    }" +
                "});");

        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        evaluateScript("var backoffIncrement = cometd.getBackoffIncrement();");
        int backoff = ((Number)javaScript.get("backoff")).intValue();
        int backoffIncrement = ((Number)javaScript.get("backoffIncrement")).intValue();
        Assertions.assertEquals(0, backoff);
        Assertions.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");

        // Time = 0.
        // First connect after handshake will fail,
        // will be retried after a backoff.
        Assertions.assertTrue(handshakeLatch.await(5000));
        Assertions.assertTrue(connectLatch.await(2 * backoffIncrement));

        // Time = 1.
        // Waits for the backoff to happen.
        Thread.sleep(backoffIncrement / 2);
        // Time = 1.5.
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        // The backoff period is always the backoff that will be waited on the *next* failure.
        Assertions.assertEquals(2 * backoffIncrement, backoff);

        connectLatch.reset(1);
        Assertions.assertTrue(connectLatch.await(2 * backoffIncrement));

        // Time = 3.
        // Another failure, backoff will be increased to 3 * backoffIncrement.
        // Waits for the backoff to happen.
        Thread.sleep(backoffIncrement / 2);
        // Time = 3.5.
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        Assertions.assertEquals(3 * backoffIncrement, backoff);

        connectLatch.reset(1);
        Assertions.assertTrue(connectLatch.await(3 * backoffIncrement));

        // Disconnect so that connect is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assertions.assertEquals("disconnected", status);

        // Be sure the connect is not retried anymore
        connectLatch.reset(1);
        Assertions.assertFalse(connectLatch.await(4 * backoffIncrement));
    }

    private static class DeleteMetaConnectExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return !Channel.META_CONNECT.equals(message.getChannel());
        }
    }
}
