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
package org.cometd.javascript;

import org.junit.Assert;
import org.junit.Test;

public class CometDCallbackPollingTest extends AbstractCometDCallbackPollingTest {
    @Test
    public void testCallbackPolling() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("" +
                "var handshakeLatch = new Latch(1);" +
                "var connectLatch = new Latch(1);" +
                "cometd.addListener('/meta/handshake', function() { handshakeLatch.countDown(); });" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("" +
                "var subscribeLatch = new Latch(1);" +
                "var messageLatch = new Latch(1);" +
                "cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });" +
                "var subscription = cometd.subscribe('/test', function() { messageLatch.countDown(); });");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("cometd.publish('/test', {});");
        Latch messageLatch = javaScript.get("messageLatch");
        Assert.assertTrue(messageLatch.await(5000));

        evaluateScript("" +
                "var unsubscribeLatch = new Latch(1);" +
                "cometd.addListener('/meta/unsubscribe', function() { unsubscribeLatch.countDown(); });" +
                "cometd.unsubscribe(subscription);");
        Latch unsubscribeLatch = javaScript.get("unsubscribeLatch");
        Assert.assertTrue(unsubscribeLatch.await(5000));

        evaluateScript("" +
                "var disconnectLatch = new Latch(1);" +
                "cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });" +
                "cometd.disconnect();");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assert.assertTrue(disconnectLatch.await(5000));
    }

    @Test
    public void testURLMaxLengthOneTooBigMessage() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.handshake();");
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = javaScript.get("publishLatch");

        evaluateScript("" +
                "var data = '';" +
                "for (var i = 0; i < 2000; ++i) {" +
                "    data += 'x';" +
                "}" +
                "cometd.addListener('/meta/publish', function(message) {" +
                "    if (!message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.publish('/foo', data);" +
                "");
        Assert.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedOneTooBigFailsWholeBatch() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.handshake();");
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(3);");
        Latch publishLatch = javaScript.get("publishLatch");

        evaluateScript("" +
                "var data = '';" +
                "for (var i = 0; i < 500; ++i) {" +
                "    data += 'x';" +
                "}" +
                "cometd.addListener('/meta/publish', function(message) {" +
                "    if (!message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.batch(function() {" +
                "    cometd.publish('/foo', data);" +
                "    cometd.publish('/foo', data);" +
                "    cometd.publish('/foo', data + data + data + data);" +
                "});" +
                "");
        Assert.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedAreSplit() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.handshake();");
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(3);");
        Latch publishLatch = javaScript.get("publishLatch");

        evaluateScript("" +
                "var data = '';" +
                "for (var i = 0; i < 500; ++i) {" +
                "    data += 'x';" +
                "}" +
                "cometd.addListener('/meta/publish', function(message) {" +
                "    if (message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.batch(function() {" +
                "    cometd.publish('/foo', data);" +
                "    cometd.publish('/foo', data);" +
                "    cometd.publish('/foo', data + data);" +
                "});" +
                "");
        Assert.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedAreSplitOrderIsKept() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("" +
                "cometd.addListener('/meta/connect', function() { connectLatch.countDown(); });" +
                "cometd.handshake();");
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("var publishLatch = new Latch(12);");
        Latch publishLatch = javaScript.get("publishLatch");

        evaluateScript("" +
                "var channel = '/foo';" +
                "var orders = [];" +
                "cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });" +
                "cometd.subscribe(channel, function(message) {" +
                "    orders.push(message.order);" +
                "    publishLatch.countDown();" +
                "});");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("" +
                "var data = '';" +
                "for (var i = 0; i < 500; ++i) {" +
                "    data += 'x';" +
                "}" +
                "cometd.addListener('/meta/publish', function(message) {" +
                "    if (message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.batch(function() {" +
                "    cometd.publish(channel, data, {order:1});" +
                "    cometd.publish(channel, data, {order:2});" +
                "    cometd.publish(channel, data + data + data, {order:3});" +
                "    cometd.publish(channel, data, {order:4});" +
                "    cometd.publish(channel, data, {order:5});" +
                "});" +
                "/* This additional publish must be sent after the split batch */" +
                "cometd.publish(channel, data, {order:6});" +
                "");
        Assert.assertTrue(publishLatch.await(5000));

        evaluateScript("window.assert([1,2,3,4,5,6].join(',') === orders.join(','), 'Order not respected ' + orders.join(','));");

        disconnect();
    }
}
