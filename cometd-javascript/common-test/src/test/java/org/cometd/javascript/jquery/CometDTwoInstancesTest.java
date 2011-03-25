package org.cometd.javascript.jquery;

import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Test;

public class CometDTwoInstancesTest extends AbstractCometDTest
{
    @Test
    public void testTwoInstances() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        evaluateScript("var handshakeLatch2 = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        Latch handshakeLatch2 = get("handshakeLatch2");

        evaluateScript("" +
                "var cometd2 = new $.Cometd('jquery');" +
                "var jsonpTransport = cometd2.unregisterTransport('long-polling');" +
                "" +
                "/* Check that the other cometd object has not been influenced */" +
                "window.assert(cometd.findTransport('long-polling') != null);" +
                "" +
                "cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');" +
                "cometd2.addListener('/meta/handshake', handshakeLatch2, 'countDown');" +
                "" +
                "cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "");
        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertFalse(handshakeLatch2.await(1000));

        String cometdURL2 = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("" +
                "cometd2.init({url: '" + cometdURL2 + "', logLevel: 'debug'});" +
                "");
        Assert.assertTrue(handshakeLatch2.await(1000));

        evaluateScript("var publishLatch = new Latch(2);");
        evaluateScript("var publishLatch2 = new Latch(2);");
        Latch publishLatch = get("publishLatch");
        Latch publishLatch2 = get("publishLatch2");
        String channelName = "/test";
        evaluateScript("" +
                "cometd.subscribe('" + channelName + "', publishLatch, 'countDown');" +
                "cometd2.subscribe('" + channelName + "', publishLatch2, 'countDown');" +
                "" +
                "cometd.publish('" + channelName + "', {});" +
                "cometd2.publish('" + channelName + "', {});" +
                "");
        Assert.assertTrue(publishLatch.await(1000));
        Assert.assertTrue(publishLatch2.await(1000));

        evaluateScript("" +
                "cometd.disconnect(true);" +
                "cometd2.disconnect(true);" +
                "");
    }
}
