package org.cometd.javascript.dojo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdDojoHitchTest extends AbstractCometdDojoTest
{
    public void testDojoHitch() throws Exception
    {
        evaluateScript("dojox.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        evaluateScript("var handshakeListener = new Listener();");
        Listener handshakeListener = get("handshakeListener");
        handshakeListener.expect(1);
        evaluateScript("dojox.cometd.addListener('/meta/handshake', handshakeListener, 'handle');");
        evaluateScript("dojox.cometd.handshake();");
        assert handshakeListener.await(1000);

        // Wait for the long poll to happen
        Thread.sleep(1000);

        evaluateScript("var testListener1 = new Listener();");
        Listener testListener1 = get("testListener1");
        testListener1.expect(1);
        evaluateScript("dojox.cometd.subscribe('/test', dojo.hitch(testListener1, 'handle'), {});");
        evaluateScript("var testListener2 = new Listener();");
        Listener testListener2 = get("testListener2");
        testListener2.expect(1);
        evaluateScript("dojox.cometd.subscribe('/test', dojo.hitch(testListener2, 'handle'));");
        evaluateScript("dojox.cometd.publish('/test', {});");
        assert testListener1.await(1000);
        assert testListener2.await(1000);

        evaluateScript("dojox.cometd.disconnect();");

        // Wait for the disconnect to happen
        Thread.sleep(1000);
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
