/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import org.junit.Assert;
import org.junit.Test;

public class CometDRedeployTest extends AbstractCometDTransportsTest {
    @Test
    public void testRedeploy() throws Exception {
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = javaScript.get("failureLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       connectLatch.countDown();" +
                "   } else {" +
                "       failureLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(connectLatch.await(5000));

        // Wait for the second connect to reach the server
        Thread.sleep(1000);

        // Redeploy the context
        handshakeLatch.reset(1);
        connectLatch.reset(1);
        context.stop();
        Assert.assertTrue(failureLatch.await(5000));
        // Assume the redeploy takes a while
        long backoffIncrement = ((Number)evaluateScript("cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(2 * backoffIncrement);
        // Restart the context
        context.start();

        long backoffPeriod = ((Number)evaluateScript("cometd.getBackoffPeriod();")).longValue();
        Assert.assertTrue(handshakeLatch.await(backoffPeriod + 2 * backoffIncrement));
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
