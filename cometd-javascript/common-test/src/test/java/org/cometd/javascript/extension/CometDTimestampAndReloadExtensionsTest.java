/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Test;

public class CometDTimestampAndReloadExtensionsTest extends AbstractCometDTest {
    @Test
    public void testReloadWithTimestamp() throws Exception {
        evaluateScript("cometd.setLogLevel('debug');");
        provideTimestampExtension();
        provideReloadExtension();

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateScript("cometd.setLogLevel('" + getLogLevel() + "');");
        provideTimestampExtension();
        provideReloadExtension();

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) { " +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        evaluateScript("cometd.disconnect(true);");
    }
}
