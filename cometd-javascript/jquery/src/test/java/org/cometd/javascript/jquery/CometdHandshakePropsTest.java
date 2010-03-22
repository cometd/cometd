package org.cometd.javascript.jquery;

import java.util.Map;

import org.cometd.Client;
import org.cometd.Message;
import org.cometd.SecurityPolicy;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractBayeux;

/**
 * Tests that hanshake properties are passed correctly during handshake
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdHandshakePropsTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeBayeux(AbstractBayeux bayeux)
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

        // Disconnect to avoid the handshake to backoff
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectLatch.await(1000));

        // We are already initialized, handshake again with a token
        handshakeLatch.reset(1);
        evaluateScript("$.cometd.handshake({ext: {token: 'test'}})");
        assertTrue(handshakeLatch.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("connected", status);

        disconnectLatch.reset(1);
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectLatch.await(1000));

        status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);
    }

    private class TokenSecurityPolicy implements SecurityPolicy
    {
        public boolean canHandshake(Message message)
        {
            Map<String, Object> ext = message.getExt(false);
            return ext != null && ext.containsKey("token");
        }

        public boolean canCreate(Client client, String s, Message message)
        {
            return true;
        }

        public boolean canSubscribe(Client client, String s, Message message)
        {
            return true;
        }

        public boolean canPublish(Client client, String s, Message message)
        {
            return true;
        }
    }
}
