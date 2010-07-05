package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 *
 */
public class CometdAutoBatchTest extends AbstractCometdJQueryTest
{
    public void testAutoBatch() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', autoBatch: true, logLevel: 'debug'});");
        assertTrue(readyLatch.await(1000));

        evaluateScript("" +
                "var channel = '/autobatch';" +
                "var autobatch = [];" +
                "var transport = $.cometd.getTransport();" +
                "var _super = transport.transportSend;" +
                "transport.transportSend = function(envelope, request)" +
                "{" +
                "   if (envelope.messages[0].channel == channel)" +
                "   {" +
                "       autobatch.push(envelope.messages.length);" +
                "   }" +
                "   _super.apply(this, arguments);" +
                "};" +
                "");

        readyLatch.reset(1);
        evaluateScript("" +
                "$.cometd.addListener('/meta/subscribe', readyLatch, 'countDown');" +
                "$.cometd.subscribe(channel, function(message)" +
                "{" +
                "   readyLatch.countDown();" +
                "});");
        assertTrue(readyLatch.await(1000));

        // Publish multiple times without batching explicitly
        // so the autobatch can trigger in
        int count = 5;
        readyLatch.reset(count);
        evaluateScript("" +
                "for (var i = 0; i < " + count + "; ++i)" +
                "   $.cometd.publish(channel, {id: i});");
        assertTrue(readyLatch.await(1000));

        evaluateScript("autobatch_assertion", "window.assert([1,4] == autobatch.join(), autobatch);");

        evaluateScript("$.cometd.disconnect(true);");
    }
}
