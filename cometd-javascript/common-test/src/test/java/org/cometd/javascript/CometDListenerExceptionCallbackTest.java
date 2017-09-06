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
package org.cometd.javascript;

import org.junit.Assert;
import org.junit.Test;

public class CometDListenerExceptionCallbackTest extends AbstractCometDTest {
    @Test
    public void testListenerExceptionCallback() throws Exception {
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "var handshakeSubscription = cometd.addListener('/meta/handshake', function() { throw 'test'; });" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "" +
                "cometd.onListenerException = function(exception, subscriptionHandle, isListener, message) {" +
                "   if (exception === 'test' && handshakeSubscription === subscriptionHandle && isListener === true) {" +
                "       this.removeListener(subscriptionHandle);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "cometd.handshake();");
        Assert.assertTrue(latch.await(5000));

        Assert.assertTrue(connectLatch.await(5000));

        disconnect();
    }

    @Test
    public void testSubscriberExceptionCallback() throws Exception {
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "var channelSubscription = undefined;" +
                "cometd.onListenerException = function(exception, subscriptionHandle, isListener, message) {" +
                "   if (exception === 'test' && channelSubscription === subscriptionHandle && isListener === false) {" +
                "       this.unsubscribe(subscriptionHandle);" +
                "   }" +
                "};" +
                "" +
                "cometd.addListener('/meta/unsubscribe', function() { latch.countDown(); });" +
                "cometd.handshake();" +
                "channelSubscription = cometd.subscribe('/test', function() { throw 'test'; });" +
                "cometd.publish('/test', {});");
        Assert.assertTrue(latch.await(5000));

        disconnect();
    }
}
