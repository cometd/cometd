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

public class CometDDisconnectInListenersTest extends AbstractCometDTest
{
    @Test
    public void testDisconnectInHandshakeListener() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});\n" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{\n" +
                "   cometd.disconnect();\n" +
                "});\n" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   connectLatch.countDown();" +
                "});\n" +
                "cometd.addListener('/meta/disconnect', function(message)" +
                "{" +
                "   disconnectLatch.countDown();" +
                "});\n" +
                "" +
                "cometd.handshake();\n" +
                "");

        // Connect must not be called
        Assert.assertFalse(connectLatch.await(1000));

        Assert.assertTrue(disconnectLatch.await(1000));
    }

    @Test
    public void testDisconnectInConnectListener() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});\n" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   if (connectLatch.count == 2) " +
                "       cometd.disconnect();" +
                "   connectLatch.countDown();" +
                "});\n" +
                "cometd.addListener('/meta/disconnect', function(message)" +
                "{" +
                "   disconnectLatch.countDown();" +
                "});\n" +
                "" +
                "cometd.handshake();\n" +
                "");

        // Connect must be called only once
        Assert.assertFalse(connectLatch.await(1000));
        Assert.assertEquals(1L, connectLatch.jsGet_count());

        Assert.assertTrue(disconnectLatch.await(1000));
    }
}
