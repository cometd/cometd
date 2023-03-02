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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDDisconnectInListenersTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectInHandshakeListener(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/handshake', () => cometd.disconnect());
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                const disconnectLatch = new Latch(1);
                cometd.addListener('/meta/disconnect', () => disconnectLatch.countDown());

                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        // Connect must not be called.
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertFalse(connectLatch.await(1000));

        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assertions.assertTrue(disconnectLatch.await(5000));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnectInConnectListener(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const connectLatch = new Latch(2);
                cometd.addListener('/meta/connect', () => {
                   if (connectLatch.getCount() === 2) {
                       cometd.disconnect();
                   }
                   connectLatch.countDown();
                });
                const disconnectLatch = new Latch(1);
                cometd.addListener('/meta/disconnect', () => disconnectLatch.countDown());

                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        // Connect must be called only once
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertFalse(connectLatch.await(1000));
        Assertions.assertEquals(1L, connectLatch.getCount());

        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assertions.assertTrue(disconnectLatch.await(5000));
    }
}
