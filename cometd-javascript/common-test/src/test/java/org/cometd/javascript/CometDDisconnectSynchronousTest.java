package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDDisconnectSynchronousTest extends AbstractCometDTest
{
    @Test
    public void testDisconnectSynchronous() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });" +
                "" +
                "cometd.handshake();");

        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("" +
                "var disconnected = false;" +
                "cometd.addListener('/meta/disconnect', function(message) { disconnected = true; });" +
                "cometd.disconnect(true);" +
                "window.assert(disconnected === true);" +
                "");
    }
}
