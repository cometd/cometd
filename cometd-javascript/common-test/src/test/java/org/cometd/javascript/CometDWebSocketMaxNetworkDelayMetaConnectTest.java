/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketMaxNetworkDelayMetaConnectTest extends AbstractCometDWebSocketTest {
    @Test
    public void testMaxNetworkDelay() throws Exception {
        long maxNetworkDelay = 2000;
        long backOffIncrement = 1000;

        bayeuxServer.addExtension(new DelayingExtension(metaConnectPeriod + backOffIncrement + maxNetworkDelay + maxNetworkDelay / 2));

        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(6);");
        Latch latch = get("latch");
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "backoffIncrement: " + backOffIncrement + ", " +
                "maxNetworkDelay: " + maxNetworkDelay + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("var connects = 0;");
        evaluateScript("var failure;");
        evaluateScript("cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "    ++connects;" +
                "    if (connects === 1 && message.successful ||" +
                "        connects === 2 && !message.successful ||" +
                "        connects === 3 && message.successful ||" +
                "        connects === 4 && !message.successful ||" +
                "        connects === 5 && message.successful ||" +
                "        connects === 6 && message.successful)" +
                "        latch.countDown();" +
                "    else if (!failure)" +
                "        failure = 'Failure at connect #' + connects;" +
                "});");

        evaluateScript("cometd.handshake();");

        // First connect returns immediately (time = 0)
        // Second connect is delayed, but client is not aware of this
        // MaxNetworkDelay elapses, second connect is failed on the client (time = metaConnectPeriod + maxNetworkDelay)
        //   + Connection is closed by client
        // Client sends third connect and is replied by the server
        // Fourth connect is sent and is held by the server
        // Delay elapses (time = metaConnectPeriod + backOffIncrement + 1.5 * maxNetworkDelay)
        // Second connect is processed on server and held
        //   + Fourth connect is canceled and not replied
        //     + Connection is closed by server
        // MaxNetworkDelay elapses, fourth connect is failed on the client (time = 2 * metaConnectPeriod + 2 * maxNetworkDelay)
        //   + Connection is closed by client
        // Client sends fifth connect and is replied by the server
        // Sixth connect is sent and is held by the server
        //   + Second connect is canceled and not replied
        // Sixth connect is replied

        Assert.assertTrue(latch.await(3 * metaConnectPeriod + 3 * maxNetworkDelay));
        evaluateScript("window.assert(failure === undefined, failure);");

        evaluateScript("cometd.disconnect(true);");
    }

    private class DelayingExtension extends BayeuxServer.Extension.Adapter {
        private final AtomicInteger connects = new AtomicInteger();
        private final long delay;

        public DelayingExtension(long delay) {
            this.delay = delay;
        }

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                int connects = this.connects.incrementAndGet();
                if (connects == 2) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException x) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
