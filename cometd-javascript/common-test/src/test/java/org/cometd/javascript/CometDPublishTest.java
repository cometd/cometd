package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDPublishTest extends AbstractCometDTest
{
    @Test
    public void testPublish() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: 'debug'})");
        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("var echoLatch = new Latch(1);");
        Latch echoLatch = get("echoLatch");
        evaluateScript("var subscription = cometd.subscribe('/echo', echoLatch, echoLatch.countDown);");
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', publishLatch, publishLatch.countDown);");

        evaluateScript("cometd.publish('/echo', 'test');");
        Assert.assertTrue(echoLatch.await(1000));
        Assert.assertTrue(publishLatch.await(1000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }
}
