package org.cometd.javascript.jquery;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.SecurityPolicy;
import org.mozilla.javascript.ScriptableObject;

/**
 * Tests that hanshake properties are passed correctly during handshake
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdHandshakePropsTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeBayeux(Bayeux bayeux)
    {
        bayeux.setSecurityPolicy(new TokenSecurityPolicy());
    }

    public void testHandshakeProps() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        evaluateScript("var handshakeListener = new Listener();");
        Listener handshakeListener = (Listener)get("handshakeListener");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeListener, handshakeListener.handle);");

        // Start comet without the token; this makes the handshake fail
        handshakeListener.expect(1);
        evaluateScript("$.cometd.handshake({})");
        assert handshakeListener.await(1000);
        // Disconnect to avoid the handshake to backoff
        evaluateScript("$.cometd.disconnect();");
        // Wait for the disconnect to happen
        Thread.sleep(1000);
        String status = evaluateScript("$.cometd.getStatus();");
        assert "disconnected".equals(status) : status;

        // We are already initialized, handshake again with a token
        handshakeListener.expect(1);
        evaluateScript("$.cometd.handshake({ext: {token: 'test'}})");
        assert handshakeListener.await(1000);

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        status = evaluateScript("$.cometd.getStatus();");
        assert "connected".equals(status) : status;

        evaluateScript("$.cometd.disconnect();");

        // Wait for the disconnect to happen
        Thread.sleep(1000);

        status = evaluateScript("$.cometd.getStatus();");
        assert "disconnected".equals(status) : status;
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
            if (latch.getCount() == 0) throw new AssertionError();
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
