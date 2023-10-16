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

public class CometDWebSocketDifferentURLsTest extends AbstractCometDWebSocketTest {
    @Test
    public void testDifferentURLs() throws Exception {
        evaluateScript("""
                const latch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                   if (message.successful) {
                       latch.countDown();
                   }
                });
                cometd.init({url: '$W', logLevel: '$L', urls: {
                    websocket: '$U'
                }});
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$W", "http://wrong/"));

        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }
}
