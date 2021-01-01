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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDHandshakeReconnectTest extends AbstractCometDTransportsTest {
    @Override
    protected void initCometDServer(String transport, Map<String, String> options) throws Exception {
        options.put(AbstractServerTransport.HANDSHAKE_RECONNECT_OPTION, String.valueOf(true));
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(1500));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(2000));
        super.initCometDServer(transport, options);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReconnectUsingHandshake(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(m) {" +
                "   if (m.successful) {" +
                "       connectLatch.countDown();" +
                "   }" +
                "});");

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("cometd.handshake();");

        Assertions.assertTrue(connectLatch.await(5000));

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        connector.stop();

        // Add a /meta/handshake listener to be sure we reconnect using handshake.
        evaluateScript("var handshakeReconnect = new Latch(1);");
        Latch handshakeReconnect = javaScript.get("handshakeReconnect");
        evaluateScript("cometd.addListener('/meta/handshake', function(m) {" +
                "   if (!m.successful) {" +
                "       handshakeReconnect.countDown();" +
                "   }" +
                "});");

        // Wait for the session to be swept (timeout + maxInterval).
        CountDownLatch sessionRemoved = new CountDownLatch(1);
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                sessionRemoved.countDown();
            }
        });

        long timeout = Long.parseLong((String)bayeuxServer.getOption(AbstractServerTransport.TIMEOUT_OPTION));
        long maxInterval = Long.parseLong((String)bayeuxServer.getOption(AbstractServerTransport.MAX_INTERVAL_OPTION));
        Assertions.assertTrue(sessionRemoved.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(handshakeReconnect.await(10000));

        // Restart the connector.
        connectLatch.reset(1);
        connector.setPort(port);
        connector.start();

        Assertions.assertTrue(connectLatch.await(20000));

        disconnect();
    }
}
