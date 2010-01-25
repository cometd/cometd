package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdExtensionExceptionCallbackTest extends AbstractCometdJQueryTest
{
    public void testOutgoingExtensionExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "$.cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "$.cometd.registerExtension('testext', {" +
                "   outgoing: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "$.cometd.onExtensionError = function(exception, extensionName, message) " +
                "{" +
                "   this.unregisterExtension(extensionName);" +
                "   latch.countDown(); " +
                "};" +
                "" +
                "$.cometd.handshake();");
        assertTrue(latch.await(1000));

        assertTrue(connectLatch.await(1000));

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500);
    }

    public void testIncomingExtensionExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "$.cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "$.cometd.registerExtension('testext', {" +
                "   incoming: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "$.cometd.onExtensionError = function(exception, extensionName, message) " +
                "{" +
                "   this.unregisterExtension(extensionName);" +
                "   latch.countDown(); " +
                "};" +
                "" +
                "$.cometd.handshake();");
        assertTrue(latch.await(1000));

        assertTrue(connectLatch.await(1000));

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500);
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
