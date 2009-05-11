package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;
import org.testng.annotations.Test;

/**
 * @version $Revision$ $Date$
 */
public class CometSubscribeTest extends AbstractJQueryCometTest
{
    @Test
    public void testSubscriptionsUnsubscriptionsForSameChannelOnlySentOnce() throws Exception
    {
        defineClass(Listener.class);
        evaluateScript("var subscribeListener = new Listener();");
        Listener subscribeListener = get("subscribeListener");
        evaluateScript("$.cometd.addListener('/meta/subscribe', subscribeListener, 'handle');");
        evaluateScript("var unsubscribeListener = new Listener();");
        Listener unsubscribeListener = get("unsubscribeListener");
        evaluateScript("$.cometd.addListener('/meta/unsubscribe', unsubscribeListener, 'handle');");

        evaluateScript("$.cometd.init({ url: '" + cometURL + "', logLevel: 'debug' });");
        Thread.sleep(1000); // Wait for long poll

        subscribeListener.expect(1);
        evaluateScript("var subscription = $.cometd.subscribe('/foo', function(message) {});");
        assert subscribeListener.await(1000);

        unsubscribeListener.expect(1);
        evaluateScript("$.cometd.unsubscribe(subscription);");
        assert unsubscribeListener.await(1000);

        // Two subscriptions to the same channel also generate only one message to the server
        subscribeListener.expect(2);
        evaluateScript("var subscription1 = $.cometd.subscribe('/foo', function(message) {});");
        evaluateScript("var subscription2 = $.cometd.subscribe('/foo', function(message) {});");
        assert !subscribeListener.await(1000);

        // No message if there are subscriptions
        unsubscribeListener.expect(0);
        evaluateScript("$.cometd.unsubscribe(subscription2);");
        assert unsubscribeListener.await(1000);

        // Expect message for last unsubscription on the channel
        unsubscribeListener.expect(1);
        evaluateScript("$.cometd.unsubscribe(subscription1);");
        assert unsubscribeListener.await(1000);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000); // Wait for disconnect
    }

    @Test
    public void testSubscriptionsRemovedOnReHandshake() throws Exception
    {
        // Listeners are not removed in case of re-handshake
        // since they are not dependent on the clientId
        defineClass(Listener.class);
        evaluateScript("var listener = new Listener();");
        Listener listener = get("listener");
        evaluateScript("$.cometd.addListener('/meta/publish', listener, 'handle');");

        evaluateScript("$.cometd.init({ url: '" + cometURL + "', logLevel: 'debug' });");
        Thread.sleep(1000); // Wait for long poll
        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000); // Wait for disconnect
        // Reconnect again
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Wait for the message on the listener
        listener.expect(1);
        evaluateScript("$.cometd.publish('/foo', {});");
        assert listener.await(1000);

        evaluateScript("var subscriber = new Listener();");
        Listener subscriber = get("subscriber");
        evaluateScript("$.cometd.subscribe('/test', subscriber, 'handle');");
        assert listener.await(1000); // Wait for subscribe to happen
        // Wait for the message on the subscriber and on the listener
        listener.expect(1);
        subscriber.expect(1);
        evaluateScript("$.cometd.publish('/test', {});");
        assert listener.await(1000);
        assert subscriber.await(1000);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000); // Wait for disconnect
        // Reconnect again
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Now the previous subscriber must be gone, but not the listener
        // Subscribe again: if the previous listener is not gone, I get 2 notifications
        evaluateScript("$.cometd.subscribe('/test', subscriber, 'handle');");
        assert listener.await(1000); // Wait for subscribe to happen
        listener.expect(1);
        subscriber.expect(2);
        evaluateScript("$.cometd.publish('/test', {});");
        assert listener.await(1000);
        assert !subscriber.await(1000);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000); // Wait for disconnect
    }

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public void expect(int messageCount)
        {
            latch = new CountDownLatch(messageCount);
        }

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_handle(Object message)
        {
            if (latch.getCount() == 0) throw new AssertionError();
            latch.countDown();
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
