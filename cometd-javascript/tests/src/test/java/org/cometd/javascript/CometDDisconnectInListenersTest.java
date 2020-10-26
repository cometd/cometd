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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDDisconnectInListenersTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectInHandshakeListener(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/handshake', function() {" +
                "   cometd.disconnect();" +
                "});" +
                "cometd.addListener('/meta/connect', function() {" +
                "   connectLatch.countDown();" +
                "});" +
                "cometd.addListener('/meta/disconnect', function() {" +
                "   disconnectLatch.countDown();" +
                "});" +
                "" +
                "cometd.handshake();" +
                "");

        // Connect must not be called
        Assertions.assertFalse(connectLatch.await(1000));

        Assertions.assertTrue(disconnectLatch.await(5000));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectInConnectListener(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function() {" +
                "   if (connectLatch.getCount() === 2) {" +
                "       cometd.disconnect();" +
                "   }" +
                "   connectLatch.countDown();" +
                "});" +
                "cometd.addListener('/meta/disconnect', function() {" +
                "   disconnectLatch.countDown();" +
                "});" +
                "" +
                "cometd.handshake();" +
                "");

        // Connect must be called only once
        Assertions.assertFalse(connectLatch.await(1000));
        Assertions.assertEquals(1L, connectLatch.getCount());

        Assertions.assertTrue(disconnectLatch.await(5000));
    }
}
