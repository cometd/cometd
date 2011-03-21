package org.cometd.javascript.extension;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Test;

public class CometDExtensionExceptionCallbackTest extends AbstractCometDTest
{
    @Test
    public void testOutgoingExtensionExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "cometd.registerExtension('testext', {" +
                "   outgoing: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "cometd.onExtensionException = function(exception, extensionName, outgoing, message) " +
                "{" +
                "   if (exception === 'test' && extensionName === 'testext' && outgoing === true)" +
                "   {" +
                "       this.unregisterExtension(extensionName);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "cometd.handshake();");
        Assert.assertTrue(latch.await(1000));

        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testIncomingExtensionExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "cometd.registerExtension('testext', {" +
                "   incoming: function(message) { throw 'test'; }" +
                "});" +
                "" +
                "cometd.onExtensionException = function(exception, extensionName, outgoing, message) " +
                "{" +
                "   if (exception === 'test' && extensionName === 'testext' && outgoing === false)" +
                "   {" +
                "       this.unregisterExtension(extensionName);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "cometd.handshake();");
        Assert.assertTrue(latch.await(1000));

        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }
}
