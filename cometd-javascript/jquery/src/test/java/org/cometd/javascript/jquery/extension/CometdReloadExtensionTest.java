package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.javascript.Latch;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;

/**
 * @version $Revision$ $Date$
 */
public class CometdReloadExtensionTest extends AbstractCometdJQueryTest
{
    public void testReloadWithLongPollingTransport() throws Exception
    {
        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "$.cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Get the clientId
        String clientId = evaluateScript("$.cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("$.cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        evaluateURL(cookiePlugin);
        evaluateURL(reloadExtension);
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        // On reload, the first long poll does not return immediately
        evaluateScript("" +
                "$.cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(2 * longPollingPeriod));

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        evaluateScript("$.cometd.disconnect(true);");

        // Be sure the cookie has been removed on disconnect
        String cookie = evaluateScript("org.cometd.COOKIE.get('org.cometd.reload');");
        assertNull(cookie);
    }

    public void testReloadWithCallbackPollingTransport() throws Exception
    {
        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

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

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "$.cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Get the clientId
        String clientId = evaluateScript("$.cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("$.cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        evaluateURL(cookiePlugin);
        evaluateURL(reloadExtension);
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + url + "', logLevel: 'debug'});");
        // Leave all the transports so that we can test if the previous transport is the one used on reload

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        // On reload, the first long poll does not return immediately
        evaluateScript("" +
                "$.cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(2 * longPollingPeriod));

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        String transportType = (String)evaluateScript("$.cometd.getTransport().getType();");
        assertEquals("callback-polling", transportType);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Callback-polling transport does not support sync disconnect

        // Be sure the cookie has been removed on disconnect
        String cookie = evaluateScript("org.cometd.COOKIE.get('org.cometd.reload');");
        assertNull(cookie);
    }

    public void testReloadWithTimestamp() throws Exception
    {
        evaluateScript("$.cometd.setLogLevel('debug');");
        // Add timestamp extension before the reload extension
        URL timestampExtension = new URL(contextURL + "/org/cometd/TimeStampExtension.js");
        evaluateURL(timestampExtension);
        URL jqueryTimestampExtension = new URL(contextURL + "/jquery/jquery.cometd-timestamp.js");
        evaluateURL(jqueryTimestampExtension);

        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Get the clientId
        String clientId = evaluateScript("$.cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("$.cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateScript("$.cometd.setLogLevel('debug');");
        evaluateURL(timestampExtension);
        evaluateURL(jqueryTimestampExtension);
        evaluateURL(cookiePlugin);
        evaluateURL(reloadExtension);
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "$.cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(2 * longPollingPeriod));

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        evaluateScript("$.cometd.disconnect(true);");
    }

    public void testReloadWithSubscriptionAndPublish() throws Exception
    {
        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = (Latch)get("latch");
        evaluateApplication();
        assertTrue(latch.await(1000));

        // Calling reload() results in the cookie being written
        evaluateScript("$.cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateURL(cookiePlugin);
        evaluateURL(reloadExtension);
        evaluateURL(jqueryReloadExtension);

        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        latch = (Latch)get("latch");
        evaluateApplication();
        // Call explicitly application initialization after reload
        evaluateScript("_init({successful: true});");
        assertTrue(latch.await(1000));

        // Check that handshake was faked
        evaluateScript("window.assert(extHandshake === null, 'extHandshake');");
        evaluateScript("window.assert(rcvHandshake !== null, 'rcvHandshake');");
        // Check that subscription was faked
        evaluateScript("window.assert(extSubscribe === null, 'extSubscribe');");
        evaluateScript("window.assert(rcvSubscribe !== null, 'rcvSubscribe');");
        // Check that publish went out
        evaluateScript("window.assert(extPublish !== null, 'extPublish');");

        evaluateScript("$.cometd.disconnect(true);");
    }

    private void evaluateApplication() throws Exception
    {
        evaluateScript("" +
                "$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});" +
                "" +
                "var extHandshake = null;" +
                "var extSubscribe = null;" +
                "var extPublish = null;" +
                "$.cometd.registerExtension('test', {" +
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
                "var rcvHandshake = null;" +
                "var rcvSubscribe = null;" +
                "var _receive = $.cometd.receive;" +
                "$.cometd.receive = function(message)" +
                "{" +
                "   $.cometd._debug('Received message', org.cometd.JSON.toJSON(message));" +
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
                "       $.cometd.batch(function()" +
                "       {" +
                "           $.cometd.subscribe('/foo', function(message) { latch.countDown(); });" +
                "           $.cometd.publish('/foo', {});" +
                "       });" +
                "   }" +
                "}" +
                "" +
                "$.cometd.addListener('/meta/connect', _init);" +
                "$.cometd.handshake();");
    }
}
