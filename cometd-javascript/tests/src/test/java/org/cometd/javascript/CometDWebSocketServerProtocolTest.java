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

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDWebSocketServerProtocolTest extends AbstractCometDWebSocketTest {
    private static final String PROTOCOL = "bayeux/1.0";

    @Override
    public void initCometDServer(String transport, Map<String, String> options) throws Exception {
        options.put("ws.protocol", PROTOCOL);
        super.initCometDServer(transport, options);
    }

    @Test
    public void testClientWithoutWebSocketProtocolServerWithWebSocketProtocol() throws Exception {
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        String channelName = "/bar";
        evaluateScript("cometd.addListener('/meta/handshake', function(message) {" +
                "   if (message.successful) {" +
                "       cometd.batch(function() {" +
                "           cometd.subscribe('" + channelName + "', function() { latch.countDown(); });" +
                "           cometd.publish('" + channelName + "', {});" +
                "       });" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake();");
        Assertions.assertFalse(latch.await(1000));
    }

    @Test
    public void testClientWithWebSocketProtocolServerWithWebSocketProtocol() throws Exception {
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "protocol: '" + PROTOCOL + "', " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        String channelName = "/bar";
        evaluateScript("cometd.addListener('/meta/handshake', function(message) {" +
                "   if (message.successful) {" +
                "       cometd.batch(function() {" +
                "           cometd.subscribe('" + channelName + "', function() { latch.countDown(); });" +
                "           cometd.publish('" + channelName + "', {});" +
                "       });" +
                "   }" +
                "});");

        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(latch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(disconnectLatch.await(5000));
    }
}
