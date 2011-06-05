package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDInitDisconnectTest extends AbstractCometDTest
{
    @Test
    public void testInitDisconnect() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var latch = new Latch(2);");
        Latch latch = get("latch");
        String script = "cometd.addListener('/**', function(message) { window.console.info(message.channel); latch.countDown(); });" +
                        // Expect 2 messages: handshake and connect
                        "cometd.handshake();";
        evaluateScript(script);
        Assert.assertTrue(latch.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("connected", status);

        // Expect disconnect and connect
        latch.reset(2);
        evaluateScript("cometd.disconnect(true);");
        Assert.assertTrue(latch.await(1000));

        status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);

        // Make sure there are no attempts to reconnect
        latch.reset(1);
        Assert.assertFalse(latch.await(longPollingPeriod * 3));
    }
}
