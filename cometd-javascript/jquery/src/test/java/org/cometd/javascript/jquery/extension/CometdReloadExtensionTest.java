package org.cometd.javascript.jquery.extension;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.javascript.jquery.AbstractCometdJQueryTest;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdReloadExtensionTest extends AbstractCometdJQueryTest
{
    public void testReload() throws Exception
    {
        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

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

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return

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

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

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

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
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

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
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

    public static class Latch extends ScriptableObject
    {
        private volatile CountDownLatch latch;

        public String getClassName()
        {
            return "Latch";
        }

        public void jsConstructor(int count)
        {
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }

        public void jsFunction_countDown()
        {
            latch.countDown();
        }
    }
}
