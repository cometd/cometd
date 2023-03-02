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
package org.cometd.javascript.dojo;

import org.cometd.javascript.Latch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDDojoTwoInstancesTest extends AbstractCometDDojoTest {
    @Test
    public void testTwoInstances() throws Exception {
        evaluateScript("const handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("const handshakeLatch2 = new Latch(1);");
        Latch handshakeLatch2 = javaScript.get("handshakeLatch2");

        evaluateScript("""
                const cometd2 = new dojox.CometD('dojo');
                cometd2.unregisterTransport('websocket');

                // Check that the other cometd object has not been influenced.
                window.assert(cometd.findTransport('websocket') !== null);

                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                cometd2.addListener('/meta/handshake', () => handshakeLatch2.countDown());

                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(handshakeLatch.await(5000));
        Assertions.assertFalse(handshakeLatch2.await(1000));

        String cometdURL2 = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                cometd2.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL2).replace("$L", getLogLevel()));
        Assertions.assertTrue(handshakeLatch2.await(5000));

        String channelName = "/test";

        evaluateScript("const subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("const subscribeLatch2 = new Latch(1);");
        Latch subscribeLatch2 = javaScript.get("subscribeLatch2");
        evaluateScript("const publishLatch = new Latch(2);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("const publishLatch2 = new Latch(2);");
        Latch publishLatch2 = javaScript.get("publishLatch2");
        evaluateScript("""
                cometd.addListener('/meta/subscribe', () => subscribeLatch.countDown());
                cometd2.addListener('/meta/subscribe', () => subscribeLatch2.countDown());
                cometd.subscribe('$C', () => publishLatch.countDown());
                cometd2.subscribe('$C', () => publishLatch2.countDown());
                """.replace("$C", channelName));
        Assertions.assertTrue(subscribeLatch.await(5000));
        Assertions.assertTrue(subscribeLatch2.await(5000));

        evaluateScript("""
                cometd.publish('$C', {});
                cometd2.publish('$C', {});
                """.replace("$C", channelName));
        Assertions.assertTrue(publishLatch.await(5000));
        Assertions.assertTrue(publishLatch2.await(5000));

        evaluateScript("""
                cometd.disconnect();
                cometd2.disconnect();
                """);
    }
}
