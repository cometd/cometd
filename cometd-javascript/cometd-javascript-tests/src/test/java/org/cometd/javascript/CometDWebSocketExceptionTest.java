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

public class CometDWebSocketExceptionTest extends AbstractCometDWebSocketTest {
    @Test
    public void testWebSocketConstructorThrowsException() throws Exception {
        evaluateScript("""
                // Need long-polling as a fallback after websocket fails.
                cometd.registerTransport('long-polling', originalTransports['long-polling']);
                
                // Replace the WebSocket constructor to throw an exception.
                window.WebSocket = () => { throw 'WebSocketException'; };
                
                cometd.configure({url: '$U', logLevel: '$L'});
                
                const wsLatch = new Latch(1);
                const lpLatch = new Latch(1);
                cometd.handshake(message => {
                   if (cometd.getTransport().getType() === 'websocket' && !message.successful) {
                       wsLatch.countDown();
                   } else if (cometd.getTransport().getType() === 'long-polling' && message.successful) {
                       lpLatch.countDown();
                   }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch wsLatch = javaScript.get("wsLatch");
        Assertions.assertTrue(wsLatch.await(5000));
        Latch lpLatch = javaScript.get("lpLatch");
        Assertions.assertTrue(lpLatch.await(5000));

        disconnect();
    }
}