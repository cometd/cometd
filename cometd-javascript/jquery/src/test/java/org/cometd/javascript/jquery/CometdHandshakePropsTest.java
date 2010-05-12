package org.cometd.javascript.jquery;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.mozilla.javascript.ScriptableObject;

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

    public void testHandshakeNoProps() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        evaluateScript("var handshakeListener = new Listener();");
        Listener handshakeListener = (Listener)get("handshakeListener");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeListener, handshakeListener.handle);");
        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = (Listener)get("disconnectListener");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);");

        // Start without the token; this makes the handshake fail
        handshakeListener.expect(1);
        evaluateScript("$.cometd.handshake({})");
        assertTrue(handshakeListener.await(1000));

        // Wait for retries etc.
        Thread.sleep(1000);

        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);
    }
    
    public void testHandshakeProps() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        evaluateScript("var handshakeListener = new Listener();");
        Listener handshakeListener = (Listener)get("handshakeListener");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeListener, handshakeListener.handle);");
        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = (Listener)get("disconnectListener");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);");

        // Start without the token; this makes the handshake fail
        handshakeListener.expect(1);
        evaluateScript("$.cometd.handshake({ext: {token: 'test'}})");
        assertTrue(handshakeListener.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("$.cometd.getStatus();");
        assertEquals("connected", status);

        disconnectListener.expect(1);
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectListener.await(1000));

        status = evaluateScript("$.cometd.getStatus();");
        assertEquals("disconnected", status);
    }

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_handle(Object message)
        {
            latch.countDown();
        }

        public void expect(int messageCount)
        {
            this.latch = new CountDownLatch(messageCount);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    private class TokenSecurityPolicy implements SecurityPolicy
    {

        @Override
        public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message)
        {
            return true;
        }

        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            Map<String, Object> ext = message.getExt();
            return ext != null && ext.containsKey("token");
        }

        @Override
        public boolean canPublish(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }

        @Override
        public boolean canSubscribe(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }
        
    }
}
