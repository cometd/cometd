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
package org.cometd.javascript.extension;

import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.cometd.server.ext.TimesyncExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDTimeSyncExtensionTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testTimeSync(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new TimesyncExtension());

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var inTimeSync = undefined;");
        evaluateScript("var outTimeSync = undefined;");
        evaluateScript("cometd.registerExtension('test', {" +
                "incoming: function(message) {" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') === 0) {" +
                "        /* The timesync from the server may be missing if it's accurate enough */" +
                "        var timesync = message.ext && message.ext.timesync;" +
                "        if (timesync) {" +
                "            inTimeSync = timesync;" +
                "        }" +
                "    }" +
                "    return message;" +
                "}," +
                "outgoing: function(message) {" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') === 0) {" +
                "        outTimeSync = message.ext && message.ext.timesync;" +
                "    }" +
                "    return message;" +
                "}" +
                "});");
        provideTimesyncExtension();

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(readyLatch.await(5000));

        // Both client and server should support timesync
        Object outTimeSync = javaScript.get("outTimeSync");
        Assertions.assertNotNull(outTimeSync);
        Object inTimeSync = javaScript.get("inTimeSync");
        Assertions.assertNotNull(inTimeSync);

        evaluateScript("var timesync = cometd.getExtension('timesync');");
        evaluateScript("var networkLag = timesync.getNetworkLag();");
        evaluateScript("var timeOffset = timesync.getTimeOffset();");
        int networkLag = ((Number)javaScript.get("networkLag")).intValue();
        Assertions.assertTrue(networkLag >= 0, String.valueOf(networkLag));

        disconnect();
    }
}
