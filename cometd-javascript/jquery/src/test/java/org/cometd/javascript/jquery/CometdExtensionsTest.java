package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdExtensionsTest extends AbstractCometdJQueryTest
{
    public void testRegisterUnregister() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var inCount = 0;");
        evaluateScript("var outCount = 0;");
        evaluateScript("$.cometd.registerExtension('testin', {" +
                "incoming: function(message) { ++inCount; return message;}" +
                "});");
        evaluateScript("$.cometd.registerExtension('testout', {" +
                "outgoing: function(message) { ++outCount; return message;}" +
                "});");
        evaluateScript("$.cometd.registerExtension('testempty', {});");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Wait for the long poll to be established
        Thread.sleep(500);

        Number inCount = get("inCount");
        Number outCount = get("outCount");
        assertEquals(2, inCount.intValue()); // handshake, connect1
        assertEquals(3, outCount.intValue()); // handshake, connect1, connect2

        Boolean unregistered = evaluateScript("$.cometd.unregisterExtension('testin');");
        assertTrue(unregistered);
        unregistered = evaluateScript("$.cometd.unregisterExtension('testout');");
        assertTrue(unregistered);

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("$.cometd.addListener('/meta/publish', function(message) { publishLatch.countDown(); });");
        evaluateScript("$.cometd.publish('/echo', 'ping');");
        assertTrue(publishLatch.await(1000));

        inCount = get("inCount");
        outCount = get("outCount");
        assertEquals(2, inCount.intValue());
        assertEquals(3, outCount.intValue());

        evaluateScript("$.cometd.disconnect(true);");
    }

    public void testExtensions() throws Exception
    {
        defineClass(Latch.class);
        defineClass(Listener.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("" +
                "var listener = new Listener();" +
                "$.cometd.registerExtension('testext', {" +
                "incoming: function(message) { listener.incoming(message); return message;}," +
                "outgoing: function(message) { listener.outgoing(message); return message;}" +
                "});");
        Listener listener = get("listener");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Wait for the long poll to be established
        // Cannot rely on latches for this, since we need to intercept the connect2
        Thread.sleep(500);

        assertEquals(3, listener.getOutgoingMessageCount()); // handshake, connect1, connect2
        assertEquals(2, listener.getIncomingMessageCount()); // handshake, connect1

        listener.reset();
        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("$.cometd.addListener('/meta/subscribe', subscribeLatch, 'countDown');");
        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = get("messageLatch");
        evaluateScript("var subscription = $.cometd.subscribe('/echo', messageLatch, 'countDown');");
        assertTrue(subscribeLatch.await(1000));
        assertEquals(1, listener.getOutgoingMessageCount()); // subscribe
        assertEquals(1, listener.getIncomingMessageCount()); // subscribe

        listener.reset();
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("$.cometd.addListener('/meta/publish', publishLatch, 'countDown');");
        evaluateScript("$.cometd.publish('/echo', 'test');");
        assertTrue(publishLatch.await(1000));
        assertTrue(messageLatch.await(1000));
        assertEquals(1, listener.getOutgoingMessageCount()); // publish
        assertEquals(2, listener.getIncomingMessageCount()); // publish, message

        listener.reset();
        evaluateScript("var unsubscribeLatch = new Latch(1);");
        Latch unsubscribeLatch = get("unsubscribeLatch");
        evaluateScript("$.cometd.addListener('/meta/unsubscribe', unsubscribeLatch, 'countDown');");
        evaluateScript("$.cometd.unsubscribe(subscription);");
        assertTrue(unsubscribeLatch.await(1000));
        assertEquals(1, listener.getOutgoingMessageCount()); // unsubscribe
        assertEquals(1, listener.getIncomingMessageCount()); // unsubscribe

        readyLatch.reset(1);
        listener.reset();
        evaluateScript("$.cometd.disconnect(true);");
        assertTrue(readyLatch.await(1000));

        assertEquals(1, listener.getOutgoingMessageCount()); // disconnect
        assertEquals(2, listener.getIncomingMessageCount()); // connect2, disconnect
    }

    public void testExtensionOrder() throws Exception
    {
        defineClass(Latch.class);

        // Default incoming extension order is reverse
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("$.cometd.registerExtension('ext1', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext2 === 1) message.ext1 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        evaluateScript("$.cometd.registerExtension('ext2', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext1 !== 1) message.ext2 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");

        evaluateScript("var ok = false;");
        evaluateScript("$.cometd.addListener('/meta/handshake', function(message) " +
                       "{" +
                       "    if (message.ext1 === 1 && message.ext2 === 1) ok = true;" +
                       "});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        assertTrue((Boolean)get("ok"));

        evaluateScript("$.cometd.disconnect(true);");

        evaluateScript("$.cometd.unregisterExtension('ext1');");
        evaluateScript("$.cometd.unregisterExtension('ext2');");
        evaluateScript("ok = false;");
        // Set incoming extension order to be forward
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug', reverseIncomingExtensions: false});");

        evaluateScript("$.cometd.registerExtension('ext1', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext2 !== 1) message.ext1 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        evaluateScript("$.cometd.registerExtension('ext2', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext1 === 1) message.ext2 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        readyLatch.reset(1);
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        assertTrue((Boolean)get("ok"));

        evaluateScript("$.cometd.disconnect(true);");
    }

    public void testExtensionRegistrationCallbacks() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var n;");
        evaluateScript("var c;");
        evaluateScript("$.cometd.registerExtension('ext1', {" +
                       "registered: function(name, cometd) " +
                       "{" +
                       "    n = name;" +
                       "    c = cometd;" +
                       "}," +
                       "unregistered: function()" +
                       "{" +
                       "    n = null;" +
                       "    c = null;" +
                       "}" +
                       "});");
        Object extName = get("n");
        assertNotNull(extName);
        Object extCometd = get("c");
        assertNotNull(extCometd);

        evaluateScript("$.cometd.unregisterExtension('ext1');");
        extName = get("n");
        assertNull(extName);
        extCometd = get("c");
        assertNull(extCometd);
    }

    public static class Listener extends ScriptableObject
    {
        private int outgoing;
        private int incoming;

        public void jsFunction_outgoing(Object message)
        {
            ++outgoing;
        }

        public void jsFunction_incoming(Object message)
        {
            ++incoming;
        }

        public String getClassName()
        {
            return "Listener";
        }

        public int getOutgoingMessageCount()
        {
            return outgoing;
        }

        public int getIncomingMessageCount()
        {
            return incoming;
        }

        public void reset()
        {
            incoming = 0;
            outgoing = 0;
        }
    }
}
