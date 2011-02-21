package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdBatchPublishTest extends AbstractCometdJQueryTest
{
    public void testBatchPublish() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");

        evaluateScript("" +
                "var _connected = false;" +
                "$.cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "    var wasConnected = _connected;" +
                "    _connected = message.successful;" +
                "    if (!wasConnected && _connected)" +
                "    {" +
                "        $.cometd.startBatch();" +
                "        $.cometd.subscribe('/echo', latch, 'countDown');" +
                "        $.cometd.publish('/echo', 'test');" +
                "        $.cometd.endBatch();" +
                "    }" +
                "});" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "$.cometd.handshake();");
        assertTrue(latch.await(1000));

        evaluateScript("$.cometd.disconnect(true);");
    }
}
