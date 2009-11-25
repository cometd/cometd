package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.AbstractBayeux;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdURLPathTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeBayeux(AbstractBayeux bayeux)
    {
        bayeux.addExtension(new BayeuxURLExtension(bayeux));
    }

    public void testURLPath() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("var handshake = undefined;");
        evaluateScript("var connect = undefined;");
        evaluateScript("$.cometd.addListener('/meta/handshake', function(message) { handshake = message; });");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { connect = message; latch.countDown(); });");
        evaluateScript("$.cometd.init({url: '" + cometdURL + "/', logLevel: 'debug'})");
        assertTrue(latch.await(1000));

        evaluateScript("window.assert(handshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(handshake.ext !== undefined, 'handshake without ext');");
        String handshakeURI = evaluateScript("handshake.ext.uri");
        assertTrue(handshakeURI.endsWith("/handshake"));

        evaluateScript("window.assert(connect !== undefined, 'connect is undefined');");
        evaluateScript("window.assert(connect.ext !== undefined, 'connect without ext');");
        String connectURI = evaluateScript("connect.ext.uri");
        assertTrue(connectURI.endsWith("/connect"));

        evaluateScript("latch = new Latch(1);");
        latch = get("latch");
        evaluateScript("var disconnect = undefined;");
        evaluateScript("$.cometd.addListener('/meta/disconnect', function(message) { disconnect = message; latch.countDown(); });");
        evaluateScript("$.cometd.disconnect();");
        assertTrue(latch.await(1000));

        evaluateScript("window.assert(disconnect !== undefined, 'disconnect is undefined');");
        evaluateScript("window.assert(disconnect.ext !== undefined, 'disconnect without ext');");
        String disconnectURI = evaluateScript("disconnect.ext.uri");
        assertTrue(disconnectURI.endsWith("/disconnect"));
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

    public static class BayeuxURLExtension implements Extension
    {
        private final Bayeux bayeux;

        public BayeuxURLExtension(Bayeux bayeux)
        {
            this.bayeux = bayeux;
        }

        public Message rcv(Client from, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client from, Message message)
        {
            return message;
        }

        public Message send(Client from, Message message)
        {
            return message;
        }

        public Message sendMeta(Client from, Message message)
        {
            if (Bayeux.META_HANDSHAKE.equals(message.getChannel()) ||
                    Bayeux.META_CONNECT.equals(message.getChannel()) ||
                    Bayeux.META_DISCONNECT.equals(message.getChannel()))
            {
                HttpServletRequest request = bayeux.getCurrentRequest();
                String uri = request.getRequestURI();
                message.getExt(true).put("uri", uri);
            }
            return message;
        }
    }
}
