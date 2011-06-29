/*
 * Copyright (c) 2010 the original author or authors.
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

import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Test;

public class CometDDojoHitchTest extends AbstractCometDTest
{
    @Test
    public void testDojoHitch() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));

        evaluateScript("var latch1 = new Latch(1);");
        Latch latch1 = get("latch1");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch1, 'countDown'), {});");
        evaluateScript("var latch2 = new Latch(1);");
        Latch latch2 = get("latch2");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch2, 'countDown'));");
        evaluateScript("cometd.publish('/test', {});");
        Assert.assertTrue(latch1.await(1000));
        Assert.assertTrue(latch2.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }
}
