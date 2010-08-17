package org.cometd.javascript.jquery;

import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.Latch;
import org.cometd.server.BayeuxServerImpl;

/**
 * Tests that hanshake properties are passed correctly during handshake
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdHandshakePropsTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.setSecurityPolicy(new TokenSecurityPolicy());
    }

    public void testHandshakeProps() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");

        // Start without the token; this makes the handshake fail
        evaluateScript("$.cometd.handshake({})");
        assertTrue(handshakeLatch.await(1000));
        // A failed handshake arrives with an advice to not reconnect
        assertEquals("disconnected", evaluateScript("$.cometd.getStatus()"));

        // We are already initialized, handshake again with a token
        handshakeLatch.reset(1);
        evaluateScript("$.cometd.handshake({ext: {token: 'test'}})");
        assertTrue(handshakeLatch.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        assertEquals("connected", evaluateScript("$.cometd.getStatus();"));

        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectLatch.await(1000));

        assertEquals("disconnected", evaluateScript("$.cometd.getStatus();"));
    }

    private class TokenSecurityPolicy implements SecurityPolicy
    {
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            Map<String, Object> ext = message.getExt();
            return ext != null && ext.containsKey("token");
        }

        public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message)
        {
            return true;
        }

        public boolean canSubscribe(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }

        public boolean canPublish(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }
    }
}
