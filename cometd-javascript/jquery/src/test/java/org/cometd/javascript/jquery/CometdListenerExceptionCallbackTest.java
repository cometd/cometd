package org.cometd.javascript.jquery;

import org.cometd.javascript.Latch;

/**
 * @version $Revision$ $Date$
 */
public class CometdListenerExceptionCallbackTest extends AbstractCometdJQueryTest
{
    public void testListenerExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "var handshakeSubscription = $.cometd.addListener('/meta/handshake', function(message) { throw 'test'; });" +
                "$.cometd.addListener('/meta/connect', function(message) { connectLatch.countDown(); });" +
                "" +
                "$.cometd.onListenerException = function(exception, subscriptionHandle, isListener, message) " +
                "{" +
                "   if (exception === 'test' && handshakeSubscription === subscriptionHandle && isListener === true)" +
                "   {" +
                "       this.removeListener(subscriptionHandle);" +
                "       latch.countDown();" +
                "   }" +
                "};" +
                "" +
                "$.cometd.handshake();");
        assertTrue(latch.await(1000));

        assertTrue(connectLatch.await(1000));

        evaluateScript("$.cometd.disconnect(true);");
    }

    public void testSubscriberExceptionCallback() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "var channelSubscription = undefined;" +
                "$.cometd.onListenerException = function(exception, subscriptionHandle, isListener, message) " +
                "{" +
                "   if (exception === 'test' && channelSubscription === subscriptionHandle && isListener === false)" +
                "   {" +
                "       this.unsubscribe(subscriptionHandle);" +
                "   }" +
                "};" +
                "" +
                "$.cometd.addListener('/meta/unsubscribe', latch, 'countDown');" +
                "$.cometd.handshake();" +
                "channelSubscription = $.cometd.subscribe('/test', function(message) { throw 'test'; });" +
                "$.cometd.publish('/test', {});");
        assertTrue(latch.await(1000));

        evaluateScript("$.cometd.disconnect(true);");
    }
}
