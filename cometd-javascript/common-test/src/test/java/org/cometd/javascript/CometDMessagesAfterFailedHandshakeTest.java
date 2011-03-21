package org.cometd.javascript;

import java.util.Map;

import junit.framework.Assert;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.Test;

public class CometDMessagesAfterFailedHandshakeTest extends AbstractCometDTest
{
    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.setSecurityPolicy(new Policy());
    }

    @Test
    public void testSubscribeAfterFailedHandshake() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = (Latch)get("handshakeLatch");
        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = (Latch)get("subscribeLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        cometd.subscribe('/foo', function(m) {});" +
                "        handshakeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/subscribe', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        subscribeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});");

        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(subscribeLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testPublishAfterFailedHandshake() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = (Latch)get("handshakeLatch");
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = (Latch)get("publishLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        cometd.publish('/foo', {});" +
                "        handshakeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/publish', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});");

        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(publishLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }

    private class Policy extends DefaultSecurityPolicy
    {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            Map<String,Object> ext = message.getExt();
            return ext != null && ext.get("authn") != null;
        }
    }
}
