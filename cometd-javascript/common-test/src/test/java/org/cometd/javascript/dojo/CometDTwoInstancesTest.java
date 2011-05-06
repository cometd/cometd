package org.cometd.javascript.dojo;

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
                "var cometd2 = new dojox.Cometd('dojo');" +
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

        String channelName = "/test";

        evaluateScript("var subscribeLatch = new Latch(1);");
        evaluateScript("var subscribeLatch2 = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        Latch subscribeLatch2 = get("subscribeLatch2");
        evaluateScript("var publishLatch = new Latch(2);");
        evaluateScript("var publishLatch2 = new Latch(2);");
        Latch publishLatch = get("publishLatch");
        Latch publishLatch2 = get("publishLatch2");
        evaluateScript("" +
                "cometd.addListener('/meta/subscribe', subscribeLatch, 'countDown');" +
                "cometd2.addListener('/meta/subscribe', subscribeLatch2, 'countDown');" +
                "cometd.subscribe('" + channelName + "', publishLatch, 'countDown');" +
                "cometd2.subscribe('" + channelName + "', publishLatch2, 'countDown');" +
                "");
        Assert.assertTrue(subscribeLatch.await(1000));
        Assert.assertTrue(subscribeLatch2.await(1000));

        evaluateScript("" +
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
