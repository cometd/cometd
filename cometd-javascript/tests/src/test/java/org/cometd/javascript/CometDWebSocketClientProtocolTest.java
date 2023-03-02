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

public class CometDWebSocketClientProtocolTest extends AbstractCometDWebSocketTest {
    private static final String PROTOCOL = "bayeux/1.0";

    @Test
    public void testClientWithoutWebSocketProtocolServerWithoutWebSocketProtocol() throws Exception {
        String channelName = "/bar";
        evaluateScript("""
                const latch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                   if (message.successful) {
                       cometd.batch(function() {
                           cometd.subscribe('$C', () => latch.countDown());
                           cometd.publish('$C', {});
                       });
                   }
                });
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$C", channelName));

        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @Test
    public void testClientWithWebSocketProtocolServerWithoutWebSocketProtocol() throws Exception {
        String channelName = "/bar";
        evaluateScript("""
                const latch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                   if (message.successful) {
                       cometd.batch(() => {
                           cometd.subscribe('$C', () => latch.countDown());
                           cometd.publish('$C', {});
                       });
                   }
                });
                cometd.init({url: '$U', logLevel: '$L', protocol: '$P'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel())
                .replace("$P", PROTOCOL).replace("$C", channelName));

        Latch latch = javaScript.get("latch");
        // The server tries to match the client protocol but if it can't tries
        // as if the client sent no protocol, which in this case will match.
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }
}
