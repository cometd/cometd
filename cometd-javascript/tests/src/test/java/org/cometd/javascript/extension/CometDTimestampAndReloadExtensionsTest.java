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
package org.cometd.javascript.extension;

import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDTimestampAndReloadExtensionsTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadWithTimestamp(String transport) throws Exception {
        initCometDServer(transport);

        provideTimestampExtension();
        provideReloadExtension();

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage(transport);

        provideTimestampExtension();
        provideReloadExtension();

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("".replace("$U", cometdURL).replace("$L", getLogLevel()));
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assertions.assertEquals(clientId, newClientId);

        disconnect();
    }
}
