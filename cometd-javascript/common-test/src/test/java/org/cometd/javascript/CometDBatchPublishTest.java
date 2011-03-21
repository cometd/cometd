package org.cometd.javascript;

import junit.framework.Assert;
import org.junit.Test;

public class CometDBatchPublishTest extends AbstractCometDTest
{
    @Test
    public void testBatchPublish() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");

        evaluateScript("" +
                "var _connected = false;" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "    var wasConnected = _connected;" +
                "    _connected = message.successful;" +
                "    if (!wasConnected && _connected)" +
                "    {" +
                "        cometd.startBatch();" +
                "        cometd.subscribe('/echo', latch, 'countDown');" +
                "        cometd.publish('/echo', 'test');" +
                "        cometd.endBatch();" +
                "    }" +
                "});" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "cometd.handshake();");
        Assert.assertTrue(latch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }
}
