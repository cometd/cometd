package org.cometd.javascript.jquery;

import java.util.Map;

import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;

/**
 * @version $Revision$ $Date$
 */
public class CometdDeliverTest extends AbstractCometdJQueryTest
{
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        new DeliverService(bayeux);
    }

    public void testDeliver() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("var pushLatch = new Latch(1);");
        Latch pushLatch = get("pushLatch");
        evaluateScript("var _data;");
        evaluateScript("$.cometd.addListener('/service/deliver', function(message) { _data = message.data; pushLatch.countDown(); });");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("var listener = $.cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("$.cometd.publish('/service/deliver', { deliver: false });");
        assertTrue(latch.await(1000));
        assertFalse(pushLatch.await(1000));
        evaluateScript("$.cometd.removeListener(listener);");
        evaluateScript("window.assert(_data === undefined);");

        evaluateScript("latch = new Latch(1);");
        evaluateScript("listener = $.cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("$.cometd.publish('/service/deliver', { deliver: true });");
        assertTrue(latch.await(1000));
        evaluateScript("$.cometd.removeListener(listener);");
        // Wait for the listener to be notified from the server
        assertTrue(pushLatch.await(1000));
        evaluateScript("window.assert(_data !== undefined);");

        evaluateScript("$.cometd.disconnect(true);");
    }

    public static class DeliverService extends AbstractService
    {
        public DeliverService(BayeuxServerImpl bayeux)
        {
            super(bayeux, "deliver");
            addService("/service/deliver", "deliver");
        }

        public void deliver(ServerSession remote, String channel, Object messageData, String messageId)
        {
            Map<String, ?> data = (Map<String, ?>)messageData;
            Boolean deliver = (Boolean) data.get("deliver");
            if (deliver)
            {
                remote.deliver(getServerSession(), channel, messageData, messageId);
            }
        }
    }
}
