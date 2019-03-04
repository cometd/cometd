/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class CometDDisconnectSynchronousTest extends AbstractCometDTest {
    @Test
    public void testDisconnectSynchronous() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });" +
                "" +
                "cometd.handshake();");

        Assert.assertTrue(readyLatch.await(5000));
        Assume.assumeThat((String)evaluateScript("cometd.getTransport().getType()"), CoreMatchers.equalTo("long-polling"));

        evaluateScript("" +
                "var disconnected = false;" +
                "cometd.addListener('/meta/disconnect', function(message) { disconnected = true; });" +
                "cometd.disconnect(true);" +
                "window.assert(disconnected === true);" +
                "");
    }
}
