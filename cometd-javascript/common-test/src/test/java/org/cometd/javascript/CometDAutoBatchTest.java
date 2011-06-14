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

package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDAutoBatchTest extends AbstractCometDTest
{
    @Test
    public void testAutoBatch() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("cometd.init({url: '" + cometdURL + "', autoBatch: true, logLevel: 'debug'});");
        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("" +
                "var channel = '/autobatch';" +
                "var autobatch = [];" +
                "var transport = cometd.getTransport();" +
                "var _super = transport.transportSend;" +
                "transport.transportSend = function(envelope, request)" +
                "{" +
                "   if (envelope.messages[0].channel == channel)" +
                "   {" +
                "       autobatch.push(envelope.messages.length);" +
                "   }" +
                "   _super.apply(this, arguments);" +
                "};" +
                "");

        readyLatch.reset(1);
        evaluateScript("" +
                "cometd.addListener('/meta/subscribe', readyLatch, 'countDown');" +
                "cometd.subscribe(channel, function(message)" +
                "{" +
                "   readyLatch.countDown();" +
                "});");
        Assert.assertTrue(readyLatch.await(1000));

        // Publish multiple times without batching explicitly
        // so the autobatch can trigger in
        int count = 5;
        readyLatch.reset(count);
        evaluateScript("" +
                "for (var i = 0; i < " + count + "; ++i)" +
                "   cometd.publish(channel, {id: i});");
        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("autobatch_assertion", "window.assert([1,4] == autobatch.join(), autobatch);");

        evaluateScript("cometd.disconnect(true);");
    }
}
