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

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDPublishHeldUntilHandshookTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishHeldUntilHandshook(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "var latch = new Latch(2);" +
                "var savedChannels;" +
                "var channels = [];" +
                "cometd.registerExtension('test', {" +
                "    outgoing: function(message) {" +
                "        channels.push(message.channel);" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/handshake', function(message) {" +
                "    cometd.publish('/bar', {});" +
                "    cometd.batch(function() {" +
                "        cometd.subscribe('/foo', function() { latch.countDown(); });" +
                "        cometd.publish('/foo', {});" +
                "    });" +
                "});" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   /* Copy the array so that from now on it is not modified anymore */" +
                "   if (!savedChannels) {" +
                "       savedChannels = channels.slice(0);" +
                "       latch.countDown();" +
                "   }" +
                "});" +
                "");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.handshake();");

        Assertions.assertTrue(latch.await(5000));

        String[] channels = javaScript.evaluate(null, "Java.to(savedChannels, 'java.lang.String[]')");
        Assertions.assertNotNull(channels);
        List<String> expectedChannels = List.of("/meta/handshake", "/bar", "/meta/subscribe", "/foo", "/meta/connect");
        Assertions.assertEquals(expectedChannels, List.of(channels));

        disconnect();
    }
}
