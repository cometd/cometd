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
    public void testSubscriptionsRemovedOnReHandshake() throws Exception
    {
        evaluateScript("$.cometd.setLogLevel('debug');");

        // Listeners are not removed in case of re-handshake
        // since they are not dependent on the clientId
        defineClass(Listener.class);
        evaluateScript("var listener = new Listener();");
        Listener listener = get("listener");
        evaluateScript("$.cometd.addListener('/meta/publish', listener, 'handle');");

        evaluateScript("$.cometd.init('" + cometURL + "')");
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
