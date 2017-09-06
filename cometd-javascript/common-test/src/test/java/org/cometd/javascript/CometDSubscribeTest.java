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

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.Assert;
import org.junit.Test;

public class CometDSubscribeTest extends AbstractCometDTransportsTest {
    @Test
    public void testSubscriptionsUnsubscriptionsForSameChannelOnlySentOnce() throws Exception {
        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });");
        evaluateScript("var unsubscribeLatch = new Latch(1);");
        Latch unsubscribeLatch = javaScript.get("unsubscribeLatch");
        evaluateScript("cometd.addListener('/meta/unsubscribe', function() { unsubscribeLatch.countDown(); });");

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for long poll

        evaluateScript("var subscription = cometd.subscribe('/foo', function() {});");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("cometd.unsubscribe(subscription);");
        Assert.assertTrue(unsubscribeLatch.await(5000));

        // Two subscriptions to the same channel also generate only one message to the server
        subscribeLatch.reset(2);
        evaluateScript("var subscription1 = cometd.subscribe('/foo', function() {});");
        evaluateScript("var subscription2 = cometd.subscribe('/foo', function() {});");
        Assert.assertFalse(subscribeLatch.await(1000));

        // No message if there are subscriptions
        unsubscribeLatch.reset(0);
        evaluateScript("cometd.unsubscribe(subscription2);");
        Assert.assertTrue(unsubscribeLatch.await(5000));

        // Expect message for last unsubscription on the channel
        unsubscribeLatch.reset(1);
        evaluateScript("cometd.unsubscribe(subscription1);");
        Assert.assertTrue(unsubscribeLatch.await(5000));

        disconnect();
    }

    @Test
    public void testSubscriptionsRemovedOnReHandshake() throws Exception {
        // Listeners are not removed in case of re-handshake
        // since they are not dependent on the clientId
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("cometd.addListener('/meta/publish', function() { latch.countDown(); });");

        evaluateScript("cometd.init({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        Thread.sleep(1000); // Wait for long poll
        disconnect();
        // Wait for the connect to return
        Thread.sleep(1000);

        // Reconnect again
        evaluateScript("cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Wait for the message on the listener
        evaluateScript("cometd.publish('/foo', {});");
        Assert.assertTrue(latch.await(5000));

        evaluateScript("var subscriber = new Latch(1);");
        Latch subscriber = javaScript.get("subscriber");
        evaluateScript("cometd.subscribe('/test', function() { subscriber.countDown(); });");
        // Wait for the message on the subscriber and on the listener
        latch.reset(1);
        evaluateScript("cometd.publish('/test', {});");
        Assert.assertTrue(latch.await(5000));
        Assert.assertTrue(subscriber.await(5000));

        disconnect();
        // Wait for the connect to return
        Thread.sleep(1000);

        // Reconnect again
        evaluateScript("cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Now the previous subscriber must be gone, but not the listener
        // Subscribe again: if the previous listener is not gone, I get 2 notifications
        evaluateScript("cometd.subscribe('/test', function() { subscriber.countDown(); });");
        latch.reset(1);
        subscriber.reset(2);
        evaluateScript("cometd.publish('/test', {});");
        Assert.assertTrue(latch.await(5000));
        Assert.assertFalse(subscriber.await(5000));

        disconnect();
    }

    @Test
    public void testDynamicResubscription() throws Exception {
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("" +
                "cometd.configure({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });" +
                "" +
                "var _subscription;" +
                "cometd.addListener('/meta/handshake', function(m) {" +
                "    if (m.successful) {" +
                "        cometd.batch(function() {" +
                "            cometd.subscribe('/static', function() { latch.countDown(); });" +
                "            if (_subscription) {" +
                "                _subscription = cometd.resubscribe(_subscription);" +
                "            }" +
                "        });" +
                "    }" +
                "});" +
                "" +
                "cometd.handshake();" +
                "");

        // Wait for /meta/connect
        Thread.sleep(1000);

        evaluateScript("cometd.publish('/static', {});");

        Assert.assertTrue(latch.await(5000));
        latch.reset(2);

        evaluateScript("" +
                "cometd.batch(function() {" +
                "    _subscription = cometd.subscribe('/dynamic', function() { latch.countDown(); });" +
                "    cometd.publish('/static', {});" +
                "    cometd.publish('/dynamic', {});" +
                "});" +
                "");

        Assert.assertTrue(latch.await(5000));
        latch.reset(2);

        stopServer();

        evaluateScript("" +
                "var connectLatch = new Latch(1);" +
                "cometd.addListener('/meta/connect', function(m) {" +
                "    if (m.successful) {" +
                "        connectLatch.countDown();" +
                "    }" +
                "});" +
                "");
        Latch connectLatch = javaScript.get("connectLatch");

        // Restart the server to trigger a re-handshake
        prepareAndStartServer(new HashMap<>());

        // Wait until we are fully reconnected
        Assert.assertTrue(connectLatch.await(5000));

        evaluateScript("" +
                "cometd.batch(function() {" +
                "    cometd.publish('/static', {});" +
                "    cometd.publish('/dynamic', {});" +
                "});" +
                "");

        Assert.assertTrue(latch.await(5000));
    }

    @Test
    public void testSubscriptionDeniedRemovesListener() throws Exception {
        final AtomicBoolean subscriptionAllowed = new AtomicBoolean(false);
        evaluateScript("var subscriptionAllowed = false;");
        bayeuxServer.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                return subscriptionAllowed.get();
            }
        });

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("" +
                "cometd.configure({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });" +
                "" +
                "cometd.addListener('/meta/subscribe', function(m) {" +
                "    /* Either both false or both true should count down the latch */" +
                "    if (subscriptionAllowed ^ !m.successful) {" +
                "        subscribeLatch.countDown();" +
                "    }" +
                "});" +
                "" +
                "cometd.handshake();" +
                "");

        // Wait for /meta/connect
        Thread.sleep(1000);

        String sessionId = evaluateScript("cometd.getClientId();");

        final String channelName = "/test";
        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = javaScript.get("messageLatch");
        evaluateScript("cometd.subscribe('" + channelName + "', function() { messageLatch.countDown(); });");
        Assert.assertTrue(subscribeLatch.await(5000));

        // Verify that messages are not received
        bayeuxServer.getSession(sessionId).deliver(null, channelName, "data");
        Assert.assertFalse(messageLatch.await(1000));

        // Reset and allow subscriptions
        subscribeLatch.reset(1);
        messageLatch.reset(1);
        subscriptionAllowed.set(true);
        evaluateScript("subscriptionAllowed = true");
        evaluateScript("cometd.subscribe('" + channelName + "', function() { messageLatch.countDown(); });");
        Assert.assertTrue(subscribeLatch.await(5000));

        // Verify that messages are received
        bayeuxServer.getChannel(channelName).publish(null, "data");
        Assert.assertTrue(messageLatch.await(1000));
    }

    @Test
    public void testSubscriptionSuccessfulInvokesCallback() throws Exception {

        final String channelName = "/foo";

        evaluateScript("var latch = new Latch(2);");
        Latch latch = javaScript.get("latch");

        evaluateScript("cometd.configure({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        evaluateScript("cometd.addListener('/meta/handshake', function() {" +
                "    var subscription = cometd.subscribe('" + channelName + "', function() {}, function(message) {" +
                "        latch.countDown();" +
                "        cometd.unsubscribe(subscription, function(message) {" +
                "            latch.countDown();" +
                "        });" +
                "    });" +
                "});");
        evaluateScript("cometd.handshake();");

        Assert.assertTrue(latch.await(5000));

        disconnect();
    }

    @Test
    public void testSubscriptionDeniedInvokesCallback() throws Exception {

        final String channelName = "/foo";
        bayeuxServer.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                if (channelName.equals(channel.getId())) {
                    return false;
                }
                return super.canSubscribe(server, session, channel, message);
            }
        });

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");

        evaluateScript("cometd.configure({ url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "' });");
        evaluateScript("cometd.handshake(function() {" +
                "    cometd.subscribe('" + channelName + "', function() {}, {}, function(message) {" +
                "        subscribeLatch.countDown();" +
                "    });" +
                "});");

        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.disconnect(function(){ disconnectLatch.countDown(); });");

        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
