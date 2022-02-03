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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.eclipse.jetty.io.Connection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDWebSocketMaxNetworkDelayMetaConnectTest extends AbstractCometDTest {
    @Test
    public void testMaxNetworkDelay() throws Exception {
        initCometDServer("websocket");

        long maxNetworkDelay = 2000;
        long backOffIncrement = 1000;

        bayeuxServer.addExtension(new DelayingExtension(metaConnectPeriod + backOffIncrement + maxNetworkDelay + maxNetworkDelay / 2));

        evaluateScript("var latch = new Latch(6);");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "backoffIncrement: " + backOffIncrement + ", " +
                "maxNetworkDelay: " + maxNetworkDelay + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");
        evaluateScript("var connects = 0;");
        evaluateScript("var failure;");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "    ++connects;" +
                "    if (connects === 1 && message.successful ||" +
                "        connects === 2 && !message.successful ||" +
                "        connects === 3 && message.successful ||" +
                "        connects === 4 && !message.successful ||" +
                "        connects === 5 && message.successful ||" +
                "        connects === 6 && message.successful) {" +
                "        latch.countDown();" +
                "    } else if (!failure) {" +
                "        failure = 'Failure at connect #' + connects;" +
                "    }" +
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

        Assertions.assertTrue(latch.await(2 * (3 * metaConnectPeriod + 3 * maxNetworkDelay)));
        evaluateScript("window.assert(failure === undefined, failure);");

        disconnect();
    }

    @Test
    public void testReconnectAfterServerExpiration() throws Exception {
        long maxInterval = 1500;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        initCometDServer("websocket", options);

        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        connector.addBean(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection) {
                opened.incrementAndGet();
            }

            @Override
            public void onClosed(Connection connection) {
                closed.incrementAndGet();
            }
        });

        long maxNetworkDelay = 2000;
        long backOffIncrement = 1000;

        long delay = metaConnectPeriod + backOffIncrement + maxNetworkDelay + maxNetworkDelay / 2;
        bayeuxServer.addExtension(new DelayingExtension(delay));

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "backoffIncrement: " + backOffIncrement + ", " +
                "maxNetworkDelay: " + maxNetworkDelay + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("cometd.handshake();");

        // The second /meta/connect will be delayed on server.
        // The server will expire the session, so further messages will trigger re-handshake.
        // The client will timeout the second /meta/connect and try again.
        // The third /meta/connect will be replied with an error "unknown_session".
        // The client will /meta/handshake again and /meta/connect again successfully.
        // Only 2 WebSocket connections should be opened.

        Thread.sleep(delay + 1000);

        // For each WebSocketConnection there is also a HttpConnection for the upgrade.
        Assertions.assertEquals(4, opened.get());

        disconnect();
        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        Assertions.assertEquals(4, closed.get());
    }

    private static class DelayingExtension implements BayeuxServer.Extension {
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
