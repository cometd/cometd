package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDDisconnectInListenersTest extends AbstractCometDTest
{
    @Test
    public void testDisconnectInHandshakeListener() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});\n" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{\n" +
                "   cometd.disconnect();\n" +
                "});\n" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   connectLatch.countDown();" +
                "});\n" +
                "cometd.addListener('/meta/disconnect', function(message)" +
                "{" +
                "   disconnectLatch.countDown();" +
                "});\n" +
                "" +
                "cometd.handshake();\n" +
                "");

        // Connect must not be called
        Assert.assertFalse(connectLatch.await(1000));

        Assert.assertTrue(disconnectLatch.await(1000));
    }

    @Test
    public void testDisconnectInConnectListener() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");

        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});\n" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   if (connectLatch.count == 2) " +
                "       cometd.disconnect();" +
                "   connectLatch.countDown();" +
                "});\n" +
                "cometd.addListener('/meta/disconnect', function(message)" +
                "{" +
                "   disconnectLatch.countDown();" +
                "});\n" +
                "" +
                "cometd.handshake();\n" +
                "");

        // Connect must be called only once
        Assert.assertFalse(connectLatch.await(1000));
        Assert.assertEquals(1L, connectLatch.jsGet_count());

        Assert.assertTrue(disconnectLatch.await(1000));
    }
}
