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

/**
 * Tests that handshake failures will backoff correctly
 */
public class CometDHandshakeFailureTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testHandshakeFailure(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new DeleteMetaHandshakeExtension());

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(2);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(2);");
        Latch failureLatch = javaScript.get("failureLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("cometd.addListener('/meta/unsuccessful', function() { failureLatch.countDown(); });");

        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        evaluateScript("var backoffIncrement = cometd.getBackoffIncrement();");
        int backoff = ((Number)javaScript.get("backoff")).intValue();
        int backoffIncrement = ((Number)javaScript.get("backoffIncrement")).intValue();
        Assertions.assertEquals(0, backoff);
        Assertions.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Assertions.assertTrue(failureLatch.await(5000));

        // There were two failures: the initial handshake failed,
        // it is retried after 0 ms, backoff incremented to 1000 ms;
        // the first retry fails immediately (second failure), next
        // retry will be after 1000 ms, backoff incremented to 2000 ms.
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        Assertions.assertEquals(2 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assertions.assertTrue(handshakeLatch.await(backoffIncrement));
        Assertions.assertTrue(failureLatch.await(backoffIncrement));

        // Another failure, backoff will be increased to 3 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        Assertions.assertEquals(3 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assertions.assertTrue(handshakeLatch.await(2 * backoffIncrement));
        Assertions.assertTrue(failureLatch.await(2 * backoffIncrement));

        // Disconnect so that handshake is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        failureLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(disconnectLatch.await(5000));
        Assertions.assertTrue(failureLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assertions.assertEquals("disconnected", status);

        // Be sure the handshake is not retried anymore
        handshakeLatch.reset(1);
        Assertions.assertFalse(handshakeLatch.await(4 * backoffIncrement));
    }

    private static class DeleteMetaHandshakeExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return !Channel.META_HANDSHAKE.equals(message.getChannel());
        }
    }
}
