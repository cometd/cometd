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
import org.junit.jupiter.api.Test;

public class CometDAutoBatchTest extends AbstractCometDLongPollingTest {
    @Test
    public void testAutoBatch() throws Exception {
        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.init({url: '$U', logLevel: '$L', autoBatch: true});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("""
                const channel = '/autobatch';
                const autobatch = [];
                const transport = cometd.getTransport();
                const _super = transport.transportSend;
                transport.transportSend = function(envelope, request) {
                   if (envelope.messages[0].channel === channel) {
                       autobatch.push(envelope.messages.length);
                   }
                   _super.apply(this, arguments);
                };
                """);

        // Reset for subscribe callback.
        readyLatch.reset(1);
        evaluateScript("cometd.subscribe(channel, () => readyLatch.countDown(), () => readyLatch.countDown());");
        Assertions.assertTrue(readyLatch.await(5000));

        // Publish multiple times without batching explicitly so the autobatch can trigger.
        int count = 5;
        // Reset for message callback.
        readyLatch.reset(count);
        evaluateScript("""
                for (let i = 0; i < $C; ++i) {
                   cometd.publish(channel, {id: i});
                }
                """.replace("$C", String.valueOf(count)));
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("autobatch_assertion", "window.assert([1,4].join(',') === autobatch.join(','), autobatch);");

        disconnect();
    }
}
