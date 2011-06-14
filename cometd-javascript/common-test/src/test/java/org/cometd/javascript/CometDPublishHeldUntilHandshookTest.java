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

import java.util.Arrays;
import java.util.List;

import junit.framework.Assert;
import org.junit.Test;

public class CometDPublishHeldUntilHandshookTest extends AbstractCometDTest
{
    @Test
    public void testPublishHeldUntilHandshook() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "var latch = new Latch(2);" +
                "var savedChannels;" +
                "var channels = [];" +
                "cometd.registerExtension('test', {" +
                "    outgoing: function(message) " +
                "    {" +
                "        channels.push(message.channel);" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    cometd.publish('/bar', {});" +
                "    cometd.batch(function()" +
                "    {" +
                "        cometd.subscribe('/foo', function(msg) { latch.countDown(); });" +
                "        cometd.publish('/foo', {});" +
                "    });" +
                "});" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   /* Copy the array so that from now on it is not modified anymore */" +
                "   if (!savedChannels)" +
                "   {" +
                "       savedChannels = channels.slice(0);" +
                "       latch.countDown();" +
                "   }" +
                "});" +
                "");
        Latch latch = (Latch)get("latch");
        evaluateScript("cometd.handshake();");

        Assert.assertTrue(latch.await(1000));

        Object jsChannels = get("savedChannels");
        Object[] channels = (Object[])Utils.jsToJava(jsChannels);
        Assert.assertNotNull(channels);
        List<Object> expectedChannels = Arrays.<Object>asList("/meta/handshake", "/bar", "/meta/subscribe", "/foo", "/meta/connect");
        Assert.assertEquals(expectedChannels, Arrays.asList(channels));

        evaluateScript("cometd.disconnect(true);");
    }
}
