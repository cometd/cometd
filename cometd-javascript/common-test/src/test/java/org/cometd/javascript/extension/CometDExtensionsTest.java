package org.cometd.javascript.extension;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Test;
import org.mozilla.javascript.ScriptableObject;

public class CometDExtensionsTest extends AbstractCometDTest
{
    @Test
    public void testRegisterUnregister() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var inCount = 0;");
        evaluateScript("var outCount = 0;");
        evaluateScript("cometd.registerExtension('testin', {" +
                "incoming: function(message) { ++inCount; return message;}" +
                "});");
        evaluateScript("cometd.registerExtension('testout', {" +
                "outgoing: function(message) { ++outCount; return message;}" +
                "});");
        evaluateScript("cometd.registerExtension('testempty', {});");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        // Wait for the long poll to be established
        Thread.sleep(500);

        Number inCount = get("inCount");
        Number outCount = get("outCount");
        Assert.assertEquals(2, inCount.intValue()); // handshake, connect1
        Assert.assertEquals(3, outCount.intValue()); // handshake, connect1, connect2

        Boolean unregistered = evaluateScript("cometd.unregisterExtension('testin');");
        Assert.assertTrue(unregistered);
        unregistered = evaluateScript("cometd.unregisterExtension('testout');");
        Assert.assertTrue(unregistered);

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', function(message) { publishLatch.countDown(); });");
        evaluateScript("cometd.publish('/echo', 'ping');");
        Assert.assertTrue(publishLatch.await(1000));

        inCount = get("inCount");
        outCount = get("outCount");
        Assert.assertEquals(2, inCount.intValue());
        Assert.assertEquals(3, outCount.intValue());

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testExtensions() throws Exception
    {
        defineClass(Latch.class);
        defineClass(Listener.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("" +
                "var listener = new Listener();" +
                "cometd.registerExtension('testext', {" +
                "incoming: function(message) { listener.incoming(message); return message;}," +
                "outgoing: function(message) { listener.outgoing(message); return message;}" +
                "});");
        Listener listener = get("listener");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        // Wait for the long poll to be established
        // Cannot rely on latches for this, since we need to intercept the connect2
        Thread.sleep(500);

        Assert.assertEquals(3, listener.getOutgoingMessageCount()); // handshake, connect1, connect2
        Assert.assertEquals(2, listener.getIncomingMessageCount()); // handshake, connect1

        listener.reset();
        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', subscribeLatch, 'countDown');");
        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = get("messageLatch");
        evaluateScript("var subscription = cometd.subscribe('/echo', messageLatch, 'countDown');");
        Assert.assertTrue(subscribeLatch.await(1000));
        Assert.assertEquals(1, listener.getOutgoingMessageCount()); // subscribe
        Assert.assertEquals(1, listener.getIncomingMessageCount()); // subscribe

        listener.reset();
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', publishLatch, 'countDown');");
        evaluateScript("cometd.publish('/echo', 'test');");
        Assert.assertTrue(publishLatch.await(1000));
        Assert.assertTrue(messageLatch.await(1000));
        Assert.assertEquals(1, listener.getOutgoingMessageCount()); // publish
        Assert.assertEquals(2, listener.getIncomingMessageCount()); // publish, message

        listener.reset();
        evaluateScript("var unsubscribeLatch = new Latch(1);");
        Latch unsubscribeLatch = get("unsubscribeLatch");
        evaluateScript("cometd.addListener('/meta/unsubscribe', unsubscribeLatch, 'countDown');");
        evaluateScript("cometd.unsubscribe(subscription);");
        Assert.assertTrue(unsubscribeLatch.await(1000));
        Assert.assertEquals(1, listener.getOutgoingMessageCount()); // unsubscribe
        Assert.assertEquals(1, listener.getIncomingMessageCount()); // unsubscribe

        readyLatch.reset(1);
        listener.reset();
        evaluateScript("cometd.disconnect(true);");
        Assert.assertTrue(readyLatch.await(1000));

        // Wait for the connect to return
        Thread.sleep(500);

        Assert.assertEquals(1, listener.getOutgoingMessageCount()); // disconnect
        Assert.assertEquals(2, listener.getIncomingMessageCount()); // connect2, disconnect
    }

    @Test
    public void testExtensionOrder() throws Exception
    {
        defineClass(Latch.class);

        // Default incoming extension order is reverse
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("cometd.registerExtension('ext1', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext2 === 1) message.ext1 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        evaluateScript("cometd.registerExtension('ext2', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext1 !== 1) message.ext2 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");

        evaluateScript("var ok = false;");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) " +
                       "{" +
                       "    if (message.ext1 === 1 && message.ext2 === 1) ok = true;" +
                       "});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        Assert.assertTrue((Boolean)get("ok"));

        evaluateScript("cometd.disconnect(true);");
        // Wait for the connect to return
        Thread.sleep(500);

        evaluateScript("cometd.unregisterExtension('ext1');");
        evaluateScript("cometd.unregisterExtension('ext2');");
        evaluateScript("ok = false;");
        // Set incoming extension order to be forward
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug', reverseIncomingExtensions: false});");

        evaluateScript("cometd.registerExtension('ext1', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext2 !== 1) message.ext1 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        evaluateScript("cometd.registerExtension('ext2', {" +
                       "incoming: function(message) " +
                       "{" +
                       "    if (message.ext1 === 1) message.ext2 = 1;" +
                       "    return message;" +
                       "} " +
                       "});");
        readyLatch.reset(1);
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        Assert.assertTrue((Boolean)get("ok"));

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testExtensionRegistrationCallbacks() throws Exception
    {
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var n;");
        evaluateScript("var c;");
        evaluateScript("cometd.registerExtension('ext1', {" +
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
        Assert.assertNotNull(extName);
        Object extCometd = get("c");
        Assert.assertNotNull(extCometd);

        evaluateScript("cometd.unregisterExtension('ext1');");
        extName = get("n");
        Assert.assertNull(extName);
        extCometd = get("c");
        Assert.assertNull(extCometd);
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
