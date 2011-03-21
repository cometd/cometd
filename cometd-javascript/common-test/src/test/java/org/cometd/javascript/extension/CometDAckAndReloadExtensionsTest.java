package org.cometd.javascript.extension;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Before;
import org.junit.Test;

public class CometDAckAndReloadExtensionsTest extends AbstractCometDTest
{
    private AckService ackService;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        ackService = new AckService(bayeux);
    }

    @Before
    public void initExtensions() throws Exception
    {
        provideMessageAcknowledgeExtension();
        provideReloadExtension();
    }

    @Test
    public void testAckAndReloadExtensions() throws Exception
    {
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        // Send a message so that the ack counter is initialized
        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("" +
                "cometd.subscribe('/test', function(message) { latch.countDown(); });" +
                "cometd.publish('/test', 'message1');");
        Assert.assertTrue(latch.await(1000));

        // Wait to allow the long poll to go to the server and tell it the ack id
        Thread.sleep(500);

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page, and simulate that a message has been received meanwhile on server
        destroyPage();
        ackService.emit("message2");
        initPage();
        initExtensions();

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        // Expect 2 messages: one sent in the middle of reload, one after reload
        evaluateScript("var latch = new Latch(2);");
        latch = get("latch");
        evaluateScript("" +
                "var testMessage = [];" +
                "cometd.addListener('/meta/handshake', function(message) " +
                "{" +
                "   cometd.batch(function() " +
                "   {" +
                "       cometd.subscribe('/test', function(message) { testMessage.push(message); latch.countDown(); });" +
                "       cometd.subscribe('/echo', function(message) { readyLatch.countDown(); });" +
                "       cometd.publish('/echo', {});" +
                "   });" +
                "});" +
                "cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        ackService.emit("message3");
        Assert.assertTrue(latch.await(1000));

        evaluateScript("window.assert(testMessage.length === 2, 'testMessage.length');");
        evaluateScript("window.assert(testMessage[0].data == 'message2', 'message2');");
        evaluateScript("window.assert(testMessage[1].data == 'message3', 'message3');");

        evaluateScript("cometd.disconnect(true);");
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
