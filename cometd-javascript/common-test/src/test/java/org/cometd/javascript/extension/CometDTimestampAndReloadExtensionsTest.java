package org.cometd.javascript.extension;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Test;

public class CometDTimestampAndReloadExtensionsTest extends AbstractCometDTest
{
    @Test
    public void testReloadWithTimestamp() throws Exception
    {
        evaluateScript("cometd.setLogLevel('debug');");
        provideTimestampExtension();
        provideReloadExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateScript("cometd.setLogLevel('debug');");
        provideTimestampExtension();
        provideReloadExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        evaluateScript("cometd.disconnect(true);");
    }
}
