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
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        StringBuilder script = new StringBuilder();
        script.append("var latch = new Latch(1);");
        script.append("var _connected;");
        script.append("function connected(message)");
        script.append("{ ");
        script.append("    var wasConnected = _connected;");
        script.append("    _connected = message.successful;");
        script.append("    if (!wasConnected && connected)");
        script.append("    {");
        script.append("        $.cometd.startBatch();");
        script.append("        $.cometd.subscribe('/echo', latch, latch.countDown); ");
        script.append("        $.cometd.publish('/echo', 'test'); ");
        script.append("        $.cometd.endBatch(); ");
        script.append("    }");
        script.append("}");
        script.append("$.cometd.addListener('/meta/connect', this, connected);");
        evaluateScript(script.toString());
        Latch latch = get("latch");

        evaluateScript("$.cometd.handshake();");
        assertTrue(latch.await(1000));

        evaluateScript("$.cometd.disconnect(true);");
    }
}
