package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.javascript.Latch;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;

/**
 * @version $Revision: 871 $ $Date$
 */
public class CometdAckAndReloadExtensionsTest extends AbstractCometdJQueryTest
{
    private AckService ackService;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        ackService = new AckService(bayeux);
    }

    public void testAckAndReloadExtensions() throws Exception
    {
        URL ackExtension = new URL(contextURL + "/org/cometd/AckExtension.js");
        evaluateURL(ackExtension);
        URL jqueryAckExtension = new URL(contextURL + "/jquery/jquery.cometd-ack.js");
        evaluateURL(jqueryAckExtension);
        URL cookiePlugin = new URL(contextURL + "/jquery/jquery.cookie.js");
        evaluateURL(cookiePlugin);
        URL reloadExtension = new URL(contextURL + "/org/cometd/ReloadExtension.js");
        evaluateURL(reloadExtension);
        URL jqueryReloadExtension = new URL(contextURL + "/jquery/jquery.cometd-reload.js");
        evaluateURL(jqueryReloadExtension);

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Send a message so that the ack counter is initialized
        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("" +
                "$.cometd.subscribe('/test', function(message) { latch.countDown(); });" +
                "$.cometd.publish('/test', 'message1');");
        assertTrue(latch.await(1000));

        // Wait to allow the long poll to go to the server and tell it the ack id
        Thread.sleep(500);

        // Calling reload() results in the cookie being written
        evaluateScript("$.cometd.reload();");

        // Reload the page, and simulate that a message has been received meanwhile on server
        destroyPage();
        ackService.emit("message2");
        initPage();

        evaluateURL(ackExtension);
        evaluateURL(jqueryAckExtension);
        evaluateURL(cookiePlugin);
        evaluateURL(reloadExtension);
        evaluateURL(jqueryReloadExtension);

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        // Expect 2 messages: one sent in the middle of reload, one after reload
        evaluateScript("var latch = new Latch(2);");
        latch = get("latch");
        evaluateScript("" +
                "var testMessage = [];" +
                "$.cometd.addListener('/meta/handshake', function(message) " +
                "{" +
                "   $.cometd.batch(function() " +
                "   {" +
                "       $.cometd.subscribe('/test', function(message) { testMessage.push(message); latch.countDown(); });" +
                "       $.cometd.subscribe('/echo', function(message) { readyLatch.countDown(); });" +
                "       $.cometd.publish('/echo', {});" +
                "   });" +
                "});" +
                "$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        ackService.emit("message3");
        assertTrue(latch.await(1000));

        evaluateScript("window.assert(testMessage.length === 2, 'testMessage.length');");
        evaluateScript("window.assert(testMessage[0].data == 'message2', 'message2');");
        evaluateScript("window.assert(testMessage[1].data == 'message3', 'message3');");

        evaluateScript("$.cometd.disconnect(true);");
    }

    private static class AckService extends AbstractService
    {
        private AckService(BayeuxServerImpl bayeux)
        {
            super(bayeux, "ack-test");
        }

        public void emit(String content)
        {
            getBayeux().getChannel("/test").publish(getServerSession(), content, null);
        }
    }
}
