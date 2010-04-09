package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 * @version $Revision$ $Date$
 */
public class CometdCallbackPollingTest extends AbstractCometdJQueryTest
{
    public void testCallbackPolling() throws Exception
    {
        defineClass(Latch.class);

        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("$.cometd.configure({url: '" + url + "', logLevel: 'debug'});");
        // Leave only the callback-polling transport
        evaluateScript("" +
                "var types = $.cometd.getTransportTypes();" +
                "for (var i = 0; i < types.length; ++i)" +
                "{" +
                "   if (types[i] !== 'callback-polling')" +
                "       $.cometd.unregisterTransport(types[i]);" +
                "}");

        evaluateScript("" +
                "var handshakeLatch = new Latch(1);" +
                "var connectLatch = new Latch(1);" +
                "$.cometd.addListener('/meta/handshake', function(m) { handshakeLatch.countDown(); });" +
                "$.cometd.addListener('/meta/connect', function(m) { connectLatch.countDown(); });");
        Latch handshakeLatch = get("handshakeLatch");
        Latch connectLatch = get("connectLatch");

        evaluateScript("$.cometd.handshake();");
        assertTrue(handshakeLatch.await(1000));
        assertTrue(connectLatch.await(1000));

        evaluateScript("" +
                "var subscribeLatch = new Latch(1);" +
                "var messageLatch = new Latch(1);" +
                "$.cometd.addListener('/meta/subscribe', function(m) { subscribeLatch.countDown(); });" +
                "var subscription = $.cometd.subscribe('/test', function(message) { messageLatch.countDown(); });");
        Latch subscribeLatch = get("subscribeLatch");
        assertTrue(subscribeLatch.await(1000));

        evaluateScript("$.cometd.publish('/test', {});");
        Latch messageLatch = get("messageLatch");
        assertTrue(messageLatch.await(1000));

        evaluateScript("" +
                "var unsubscribeLatch = new Latch(1);" +
                "$.cometd.addListener('/meta/unsubscribe', function(m) { unsubscribeLatch.countDown(); });" +
                "$.cometd.unsubscribe(subscription);");
        Latch unsubscribeLatch = get("unsubscribeLatch");
        assertTrue(unsubscribeLatch.await(1000));

        evaluateScript("" +
                "var disconnectLatch = new Latch(1);" +
                "$.cometd.addListener('/meta/disconnect', function(m) { disconnectLatch.countDown(); });" +
                "$.cometd.disconnect();");
        Latch disconnectLatch = get("disconnectLatch");
        assertTrue(disconnectLatch.await(1000));
    }
}
