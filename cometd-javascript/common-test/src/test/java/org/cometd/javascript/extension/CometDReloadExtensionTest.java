package org.cometd.javascript.extension;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Before;
import org.junit.Test;

public class CometDReloadExtensionTest extends AbstractCometDTest
{
    @Before
    public void initExtension() throws Exception
    {
        provideReloadExtension();
    }

    @Test
    public void testReloadDoesNotExpire() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Wait that the long poll is established before reloading
        Thread.sleep(longPollingPeriod / 2);

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("var expireLatch = new Latch(1);");
        Latch expireLatch = get("expireLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) " +
                "       readyLatch.countDown();" +
                "   else" +
                "       expireLatch.countDown();" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        // Make sure that reloading will not expire the client on the server
        Assert.assertFalse(expireLatch.await(expirationPeriod + longPollingPeriod));
    }

    @Test
    public void testReloadWithLongPollingTransport() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        evaluateScript("cometd.disconnect(true);");

        // Be sure the cookie has been removed on disconnect
        Boolean noCookie = evaluateScript("org.cometd.COOKIE.get('org.cometd.reload') == null;");
        Assert.assertTrue(noCookie);
    }

    @Test
    public void testReloadWithCallbackPollingTransport() throws Exception
    {
        defineClass(Latch.class);
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: 'debug'});");
        // Leave only the callback-polling transport
        evaluateScript("" +
                "var types = cometd.getTransportTypes();" +
                "for (var i = 0; i < types.length; ++i)" +
                "{" +
                "   if (types[i] !== 'callback-polling')" +
                "       cometd.unregisterTransport(types[i]);" +
                "}");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: 'debug'});");
        // Leave all the transports so that we can test if the previous transport is the one used on reload

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(10000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        String transportType = (String)evaluateScript("cometd.getTransport().getType();");
        Assert.assertEquals("callback-polling", transportType);

        evaluateScript("cometd.disconnect();");
        Thread.sleep(500); // Callback-polling transport does not support sync disconnect

        // Be sure the cookie has been removed on disconnect
        Boolean noCookie = evaluateScript("org.cometd.COOKIE.get('org.cometd.reload') == null;");
        Assert.assertTrue(noCookie);
    }

    @Test
    public void testReloadWithSubscriptionAndPublish() throws Exception
    {
        defineClass(Latch.class);
        evaluateApplication();
        Latch latch = (Latch)get("latch");
        Assert.assertTrue(latch.await(10000));

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateApplication();
        latch = (Latch)get("latch");
        Assert.assertTrue(latch.await(10000));

        // Check that handshake was faked
        evaluateScript("window.assert(extHandshake === null, 'extHandshake');");
        evaluateScript("window.assert(rcvHandshake !== null, 'rcvHandshake');");
        // Check that subscription went out
        evaluateScript("window.assert(extSubscribe !== null, 'extSubscribe');");
        evaluateScript("window.assert(rcvSubscribe === null, 'rcvSubscribe');");
        // Check that publish went out
        evaluateScript("window.assert(extPublish !== null, 'extPublish');");

        evaluateScript("cometd.disconnect(true);");
    }

    private void evaluateApplication() throws Exception
    {
        evaluateScript("" +
                "var latch = new Latch(1);" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "" +
                "var extHandshake = null;" +
                "var extSubscribe = null;" +
                "var extPublish = null;" +
                "cometd.registerExtension('test', {" +
                "   outgoing: function(message) " +
                "   {" +
                "       if (message.channel === '/meta/handshake')" +
                "           extHandshake = message;" +
                "       else if (message.channel === '/meta/subscribe')" +
                "           extSubscribe = message;" +
                "       else if (!/^\\/meta\\//.test(message.channel))" +
                "           extPublish = message;" +
                "   }" +
                "});" +
                "" +
                "/* Override receive() since it's the method called by the extension to fake responses */" +
                "var rcvHandshake = null;" +
                "var rcvSubscribe = null;" +
                "var _receive = cometd.receive;" +
                "cometd.receive = function(message)" +
                "{" +
                "   cometd._debug('Received message', org.cometd.JSON.toJSON(message));" +
                "   _receive(message);" +
                "   if (message.channel === '/meta/handshake')" +
                "       rcvHandshake = message;" +
                "   else if (message.channel === '/meta/subscribe')" +
                "       rcvSubscribe = message;" +
                "};" +
                "" +
                "var _connected = false;" +
                "function _init(message)" +
                "{" +
                "   var wasConnected = _connected;" +
                "   _connected = message.successful;" +
                "   if (!wasConnected && _connected)" +
                "   {" +
                "       cometd.batch(function()" +
                "       {" +
                "           cometd.subscribe('/foo', function(message) { latch.countDown(); });" +
                "           cometd.publish('/foo', {});" +
                "       });" +
                "   }" +
                "}" +
                "" +
                "cometd.addListener('/meta/connect', _init);" +
                "cometd.handshake();");
    }
}
