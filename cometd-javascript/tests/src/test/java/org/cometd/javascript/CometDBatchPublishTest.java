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

public class CometDBatchPublishTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testBatchPublish(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("const latch = new Latch(1);");
        Latch latch = javaScript.get("latch");

        evaluateScript("""
                let _connected = false;
                cometd.addListener('/meta/connect', message => {
                    const wasConnected = _connected;
                    _connected = message.successful;
                    if (!wasConnected && _connected) {
                        cometd.startBatch();
                        cometd.subscribe('/echo', () => latch.countDown());
                        cometd.publish('/echo', 'test');
                        cometd.endBatch();
                    }
                });
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }
}
