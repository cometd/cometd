/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that handshake failures will backoff correctly
 */
public class CometDHandshakeFailureTest extends AbstractCometDTransportsTest {
    @Test
    public void testHandshakeFailure() throws Exception {
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
        final int backoffIncrement = ((Number)javaScript.get("backoffIncrement")).intValue();
        Assert.assertEquals(0, backoff);
        Assert.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));

        // There were two failures: the initial handshake failed,
        // it is retried after 0 ms, backoff incremented to 1000 ms;
        // the first retry fails immediately (second failure), next
        // retry will be after 1000 ms, backoff incremented to 2000 ms.
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        Assert.assertEquals(2 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(handshakeLatch.await(backoffIncrement));
        Assert.assertTrue(failureLatch.await(backoffIncrement));

        // Another failure, backoff will be increased to 3 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)javaScript.get("backoff")).intValue();
        Assert.assertEquals(3 * backoffIncrement, backoff);

        handshakeLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(handshakeLatch.await(2 * backoffIncrement));
        Assert.assertTrue(failureLatch.await(2 * backoffIncrement));

        // Disconnect so that handshake is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        failureLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);

        // Be sure the handshake is not retried anymore
        handshakeLatch.reset(1);
        Assert.assertFalse(handshakeLatch.await(4 * backoffIncrement));
    }

    private static class DeleteMetaHandshakeExtension extends BayeuxServer.Extension.Adapter {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return !Channel.META_HANDSHAKE.equals(message.getChannel());
        }
    }
}
