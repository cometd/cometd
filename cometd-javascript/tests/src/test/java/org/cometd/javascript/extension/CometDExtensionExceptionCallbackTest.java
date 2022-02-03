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
package org.cometd.javascript.extension;

import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;


public class CometDExtensionExceptionCallbackTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testOutgoingExtensionExceptionCallback(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.registerExtension('testext', {" +
                "   outgoing: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "cometd.onExtensionException = function(exception, extensionName, outgoing, message) {" +
                "   if (exception === 'test' && extensionName === 'testext' && outgoing === true) {" +
                "       this.unregisterExtension(extensionName);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "cometd.handshake();");
        Assertions.assertTrue(latch.await(5000));

        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIncomingExtensionExceptionCallback(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.registerExtension('testext', {" +
                "   incoming: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "cometd.onExtensionException = function(exception, extensionName, outgoing, message) {" +
                "   if (exception === 'test' && extensionName === 'testext' && outgoing === false) {" +
                "       this.unregisterExtension(extensionName);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "cometd.handshake();");
        Assertions.assertTrue(latch.await(5000));

        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }
}
