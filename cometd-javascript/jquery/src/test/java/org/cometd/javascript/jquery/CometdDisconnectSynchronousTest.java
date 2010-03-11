package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdDisconnectSynchronousTest extends AbstractCometdJQueryTest
{
    public void testDisconnectSynchronous() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "$.cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "" +
                "$.cometd.handshake();");

        assertTrue(connectLatch.await(1000));

        evaluateScript("" +
                "var disconnected = false;" +
                "$.cometd.addListener('/meta/disconnect', function(message) { disconnected = true; });" +
                "$.cometd.disconnect(true);" +
                "window.assert(disconnected === true);" +
                "");
    }

    public static class Latch extends ScriptableObject
    {
        private volatile CountDownLatch latch;

        public String getClassName()
        {
            return "Latch";
        }

        public void jsConstructor(int count)
        {
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }

        public void jsFunction_countDown()
        {
            latch.countDown();
        }
    }
}
