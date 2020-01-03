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

import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketDifferentURLsTest extends AbstractCometDWebSocketTest {
    @Test
    public void testDifferentURLs() throws Exception {
        evaluateScript("cometd.configure({" +
                "url: 'http://wrong/', " +
                "urls: {" +
                "    'websocket': '" + cometdURL + "'" +
                "}," +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) {" +
                "   if (message.successful) {" +
                "       latch.countDown();" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(latch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
