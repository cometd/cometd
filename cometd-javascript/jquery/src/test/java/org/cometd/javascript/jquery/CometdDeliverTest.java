package org.cometd.javascript.jquery;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.BayeuxService;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdDeliverTest extends AbstractCometdJQueryTest
{
    private DeliverService service;

    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        service = new DeliverService(bayeux);
    }

    public void testDeliver() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var _data;");
        evaluateScript("$.cometd.addListener('/deliver', function(message) { _data = message.data; });");

        evaluateScript("$.cometd.handshake();");

        // Wait for the handshake to complete
        Thread.sleep(1000);

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("var listener = $.cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("$.cometd.publish('/deliver', { deliver: false });");
        assertTrue(latch.await(1000));
        evaluateScript("$.cometd.removeListener(listener);");
        evaluateScript("window.assert(_data === undefined);");

        evaluateScript("latch = new Latch(1);");
        evaluateScript("listener = $.cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("$.cometd.publish('/deliver', { deliver: true });");
        assertTrue(latch.await(1000));
        evaluateScript("$.cometd.removeListener(listener);");
        // Wait for the listener to be notified
        Thread.sleep(500);
        evaluateScript("window.assert(_data !== undefined);");

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500);
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

    public static class DeliverService extends BayeuxService
    {
        public DeliverService(BayeuxServerImpl bayeux)
        {
            super(bayeux, "deliver");
            subscribe("/deliver", "deliver");
        }

        public void deliver(ServerSession remote, String channel, Object messageData, String messageId)
        {
            Map<String, ?> data = (Map<String,?>)messageData;
            Boolean deliver = (Boolean) data.get("deliver");
            if (deliver)
            {
                remote.deliver(getClient(), channel, messageData, messageId);
            }
        }
    }
}
