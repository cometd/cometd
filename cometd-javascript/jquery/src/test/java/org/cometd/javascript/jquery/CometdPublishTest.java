package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdPublishTest extends AbstractCometdJQueryTest
{
    public void testPublish() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', logLevel: 'debug'})");
        assertTrue(readyLatch.await(1000));

        evaluateScript("var echoLatch = new Latch(1);");
        Latch echoLatch = get("echoLatch");
        evaluateScript("var subscription = $.cometd.subscribe('/echo', echoLatch, echoLatch.countDown);");
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("$.cometd.addListener('/meta/publish', publishLatch, publishLatch.countDown);");

        evaluateScript("$.cometd.publish('/echo', 'test');");
        assertTrue(echoLatch.await(1000));
        assertTrue(publishLatch.await(1000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectLatch.await(1000));
        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);
    }
}
