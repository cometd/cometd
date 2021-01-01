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
package org.cometd.javascript.dojo;

import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("Failing due to issue #742")
public class CometDDojoHitchTest extends AbstractCometDDojoTest {
    @Test
    public void testDojoHitch() throws Exception {
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));

        evaluateScript("var latch1 = new Latch(1);");
        Latch latch1 = javaScript.get("latch1");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch1, 'countDown'), {});");
        evaluateScript("var latch2 = new Latch(1);");
        Latch latch2 = javaScript.get("latch2");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch2, 'countDown'));");
        evaluateScript("cometd.publish('/test', {});");
        Assert.assertTrue(latch1.await(5000));
        Assert.assertTrue(latch2.await(5000));

        disconnect();
    }
}
