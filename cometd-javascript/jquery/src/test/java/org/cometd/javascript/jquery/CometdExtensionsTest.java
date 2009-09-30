package org.cometd.javascript.jquery;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdExtensionsTest extends AbstractCometdJQueryTest
{
    public void testRegisterUnregister() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        evaluateScript("var inCount = 0;");
        evaluateScript("var outCount = 0;");
        evaluateScript("$.cometd.registerExtension('testin', {" +
                "incoming: function(message) { ++inCount; return message;}" +
                "});");
        evaluateScript("$.cometd.registerExtension('testout', {" +
                "outgoing: function(message) { ++outCount; return message;}" +
                "});");
        evaluateScript("$.cometd.registerExtension('testempty', {});");

        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

        Number inCount = get("inCount");
        Number outCount = get("outCount");
        assertEquals(2, inCount.intValue()); // handshake, connect1
        assertEquals(3, outCount.intValue()); // handshake, connect1, connect2

        Boolean unregistered = evaluateScript("$.cometd.unregisterExtension('testin');");
        assertTrue(unregistered);
        unregistered = evaluateScript("$.cometd.unregisterExtension('testout');");
        assertTrue(unregistered);

        evaluateScript("$.cometd.publish('/echo', 'ping');");
        Thread.sleep(500); // Wait for the publish to return
        inCount = get("inCount");
        outCount = get("outCount");
        assertEquals(2, inCount.intValue());
        assertEquals(3, outCount.intValue());

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }

    public void testExtensions() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);

        StringBuilder script = new StringBuilder();
        script.append("var listener = new Listener();");
        script.append("$.cometd.registerExtension('testext', {" +
                "incoming: function(message) { listener.incoming(message); return message;}," +
                "outgoing: function(message) { listener.outgoing(message); return message;}" +
                "});");
        evaluateScript(script.toString());
        Listener listener = get("listener");

        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll
        assertEquals(3, listener.getOutgoingMessageCount()); // handshake, connect1, connect2
        assertEquals(2, listener.getIncomingMessageCount()); // handshake, connect1

        listener.reset();
        script.setLength(0);
        script.append("var subscription = $.cometd.subscribe('/echo', window.console, window.console.debug);");
        evaluateScript(script.toString());
        Thread.sleep(500); // Wait for subscribe to happen
        assertEquals(1, listener.getOutgoingMessageCount()); // subscribe
        assertEquals(1, listener.getIncomingMessageCount()); // subscribe

        listener.reset();
        script.setLength(0);
        script.append("$.cometd.publish('/echo', 'test');");
        evaluateScript(script.toString());
        Thread.sleep(500); // Wait for subscribe to happen
        assertEquals(1, listener.getOutgoingMessageCount()); // publish
        assertEquals(2, listener.getIncomingMessageCount()); // publish, message

        listener.reset();
        script.setLength(0);
        script.append("$.cometd.unsubscribe(subscription);");
        evaluateScript(script.toString());
        Thread.sleep(500); // Wait for subscribe to happen
        assertEquals(1, listener.getOutgoingMessageCount()); // unsubscribe
        assertEquals(1, listener.getIncomingMessageCount()); // unsubscribe

        listener.reset();
        script.setLength(0);
        script.append("$.cometd.disconnect();");
        evaluateScript(script.toString());
        Thread.sleep(500); // Wait for disconnect to happen
        assertEquals(1, listener.getOutgoingMessageCount()); // disconnect
        assertEquals(2, listener.getIncomingMessageCount()); // connect2, disconnect
    }

    public void testExtensionOrder() throws Exception
    {
        // Default incoming extension order is reverse
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");

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
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        assertTrue((Boolean)get("ok"));

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000);

        evaluateScript("$.cometd.unregisterExtension('ext1');");
        evaluateScript("$.cometd.unregisterExtension('ext2');");
        evaluateScript("ok = false;");
        // Set incoming extension order to be forward
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug', reverseIncomingExtensions: false});");

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

        evaluateScript("$.cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        assertTrue((Boolean)get("ok"));

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(1000);
    }

    public void testExtensionRegistrationCallbacks() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
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
