package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.javascript.Latch;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;

/**
 * @version $Revision$ $Date$
 */
public class CometdTimestampAndReloadExtensionsTest extends AbstractCometdJQueryTest
{
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
        assertTrue(readyLatch.await(1000));

        String newClientId = evaluateScript("$.cometd.getClientId();");
        assertEquals(clientId, newClientId);

        evaluateScript("$.cometd.disconnect(true);");
    }
}
