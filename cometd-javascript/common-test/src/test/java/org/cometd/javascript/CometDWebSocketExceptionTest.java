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

import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketExceptionTest extends AbstractCometDWebSocketTest {
    @Test
    public void testWebSocketConstructorThrowsException() throws Exception {
        // Need long-polling as a fallback after websocket fails
        evaluateScript("cometd.registerTransport('long-polling', originalTransports['long-polling']);");

        // Replace the WebSocket constructor to throw an exception
        evaluateScript("window.WebSocket = function() { throw 'WebSocketException'; };");

        defineClass(Latch.class);

        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var wsLatch = new Latch(1);");
        Latch wsLatch = get("wsLatch");
        evaluateScript("var lpLatch = new Latch(1);");
        Latch lpLatch = get("lpLatch");
        evaluateScript("cometd.handshake(function(message) " +
                "{ " +
                "   if (cometd.getTransport().getType() === 'websocket' && !message.successful)" +
                "   {" +
                "       wsLatch.countDown();" +
                "   }" +
                "   else if (cometd.getTransport().getType() === 'long-polling' && message.successful)" +
                "   {" +
                "       lpLatch.countDown();" +
                "   }" +
                "});");

        Assert.assertTrue(wsLatch.await(5000));
        Assert.assertTrue(lpLatch.await(5000));

        evaluateScript("cometd.disconnect(1000)");
    }
}
