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

public class CometDConnectTemporaryFailureTest extends AbstractCometDTransportsTest {
    @Test
    public void testConnectTemporaryFailure() throws Exception {
        bayeuxServer.addExtension(new DeleteMetaConnectExtension());

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = javaScript.get("failureLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("" +
                "var wasConnected = false;" +
                "var connected = false;" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   window.console.debug('metaConnect: was', wasConnected, 'is', connected, 'message', message.successful);" +
                "   wasConnected = connected;" +
                "   connected = message.successful === true;" +
                "   if (!wasConnected && connected) {" +
                "       connectLatch.countDown();" +
                "   } else if (wasConnected && !connected) {" +
                "       failureLatch.countDown();" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(connectLatch.await(5000));
        Assert.assertEquals(1L, failureLatch.getCount());

        handshakeLatch.reset(1);
        connectLatch.reset(1);
        // Wait for the connect to temporarily fail
        Assert.assertTrue(failureLatch.await(metaConnectPeriod * 2));
        Assert.assertEquals(1L, handshakeLatch.getCount());
        Assert.assertEquals(1L, connectLatch.getCount());

        // Implementation will backoff the connect attempt
        long backoff = ((Number)evaluateScript("cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(backoff);

        failureLatch.reset(1);
        // Reconnection will trigger /meta/connect
        Assert.assertTrue(connectLatch.await(5000));
        Assert.assertEquals(1L, handshakeLatch.getCount());
        Assert.assertEquals(1L, failureLatch.getCount());

        disconnect();
    }

    private static class DeleteMetaConnectExtension implements BayeuxServer.Extension {
        private int connects;

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                ++connects;
                if (connects == 3) {
                    return false;
                }
            }
            return true;
        }
    }
}
