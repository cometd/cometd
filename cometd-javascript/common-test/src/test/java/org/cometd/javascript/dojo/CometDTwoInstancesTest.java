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
package org.cometd.javascript.dojo;

import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Test;

public class CometDTwoInstancesTest extends AbstractCometDTest {
    @Test
    public void testTwoInstances() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        evaluateScript("var handshakeLatch2 = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        Latch handshakeLatch2 = get("handshakeLatch2");

        evaluateScript("" +
                "var cometd2 = new dojox.CometD('dojo');" +
                "var jsonpTransport = cometd2.unregisterTransport('long-polling');" +
                "" +
                "/* Check that the other cometd object has not been influenced */" +
                "window.assert(cometd.findTransport('long-polling') != null);" +
                "" +
                "cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');" +
                "cometd2.addListener('/meta/handshake', handshakeLatch2, 'countDown');" +
                "" +
                "cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertFalse(handshakeLatch2.await(1000));

        String cometdURL2 = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("" +
                "cometd2.init({url: '" + cometdURL2 + "', logLevel: '" + getLogLevel() + "'});" +
                "");
        Assert.assertTrue(handshakeLatch2.await(5000));

        String channelName = "/test";

        evaluateScript("var subscribeLatch = new Latch(1);");
        evaluateScript("var subscribeLatch2 = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        Latch subscribeLatch2 = get("subscribeLatch2");
        evaluateScript("var publishLatch = new Latch(2);");
        evaluateScript("var publishLatch2 = new Latch(2);");
        Latch publishLatch = get("publishLatch");
        Latch publishLatch2 = get("publishLatch2");
        evaluateScript("" +
                "cometd.addListener('/meta/subscribe', subscribeLatch, 'countDown');" +
                "cometd2.addListener('/meta/subscribe', subscribeLatch2, 'countDown');" +
                "cometd.subscribe('" + channelName + "', publishLatch, 'countDown');" +
                "cometd2.subscribe('" + channelName + "', publishLatch2, 'countDown');" +
                "");
        Assert.assertTrue(subscribeLatch.await(5000));
        Assert.assertTrue(subscribeLatch2.await(5000));

        evaluateScript("" +
                "cometd.publish('" + channelName + "', {});" +
                "cometd2.publish('" + channelName + "', {});" +
                "");
        Assert.assertTrue(publishLatch.await(5000));
        Assert.assertTrue(publishLatch2.await(5000));

        evaluateScript("" +
                "cometd.disconnect(true);" +
                "cometd2.disconnect(true);" +
                "");
    }
}
