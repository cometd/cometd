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
package org.cometd.javascript;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDWebSocketNetworkDelayListenerTest extends AbstractCometDWebSocketTest {
    @Test
    public void testNetworkDelayListener() throws Exception {
        long maxNetworkDelay = 1000;
        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            private int metaConnects;

            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    if (++metaConnects == 2) {
                        sleep(3 * maxNetworkDelay / 2);
                    }
                }
                return true;
            }
        });

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "logLevel: '" + getLogLevel() + "'," +
                "maxNetworkDelay: " + maxNetworkDelay +
                "});");

        evaluateScript("var transportLatch = new Latch(1);");
        Latch transportLatch = javaScript.get("transportLatch");
        evaluateScript("cometd.addTransportListener('timeout', function() {" +
                "transportLatch.countDown();" +
                "return " + maxNetworkDelay + ";" +
                "});");

        String channelName = "/delayListener";
        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = javaScript.get("messageLatch");
        evaluateScript("cometd.addListener('" + channelName + "', function() { messageLatch.countDown(); });");

        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(m) {" +
                "  if (m.successful) {" +
                "    connectLatch.countDown(); " +
                "  } else {" +
                "    cometd._info('/meta/connect failure', m);" +
                "  }" +
                "});");

        evaluateScript("cometd.handshake();");

        Thread.sleep(metaConnectPeriod / 2);

        String sessionId = evaluateScript("cometd.getClientId();");
        bayeuxServer.getSession(sessionId).deliver(null, channelName, "DATA", Promise.noop());

        Assertions.assertTrue(messageLatch.await(5000));

        // Verify that the transport listener is invoked.
        Assertions.assertTrue(transportLatch.await(metaConnectPeriod));

        // Verify that we are still connected.
        Assertions.assertTrue(connectLatch.await(2 * metaConnectPeriod));
        boolean disconnected = evaluateScript("cometd.isDisconnected();");
        Assertions.assertFalse(disconnected);

        disconnect();
    }
}
