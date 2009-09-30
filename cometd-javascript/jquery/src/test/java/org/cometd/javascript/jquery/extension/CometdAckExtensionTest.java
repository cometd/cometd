package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.Bayeux;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;
import org.cometd.server.BayeuxService;
import org.cometd.server.ext.AcknowledgedMessagesExtension;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdAckExtensionTest extends AbstractCometdJQueryTest
{
    private AckService ackService;

    @Override
    protected void customizeBayeux(Bayeux bayeux)
    {
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        ackService = new AckService(bayeux);
    }

    public void testClientSupportsAckExtension() throws Exception
    {
        URL ackExtensionURL = new URL(contextURL + "/org/cometd/AckExtension.js");
        evaluateURL(ackExtensionURL);

        evaluateScript("var cometd = $.cometd;");
        evaluateScript("cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        evaluateScript("var ackExt = new org.cometd.AckExtension();");
        evaluateScript("cometd.registerExtension('myack', ackExt);");

        // Check that during handshake the ack extension capability is sent to server
        evaluateScript("var clientSupportsAck = false;");
        evaluateScript("cometd.registerExtension('test', {" +
                "outgoing: function(message)" +
                "{" +
                "   if (message.channel == '/meta/handshake')" +
                "   {" +
                "       clientSupportsAck = message.ext && message.ext.ack;" +
                "   }" +
                "   return message;" +
                "}" +
                "});");
        evaluateScript("cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll
        Boolean clientSupportsAck = get("clientSupportsAck");
        assert clientSupportsAck;
        evaluateScript("cometd.unregisterExtension('test');");

        evaluateScript("cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }

    public void testAcknowledgement() throws Exception
    {
        URL ackExtensionURL = new URL(contextURL + "/org/cometd/AckExtension.js");
        evaluateURL(ackExtensionURL);

        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.registerExtension('ack', new org.cometd.AckExtension());");

        evaluateScript("var inAckId = undefined;");
        evaluateScript("var outAckId = undefined;");
        evaluateScript("$.cometd.registerExtension('test', {" +
                "incoming: function(message)" +
                "{" +
                "   if (message.channel == '/meta/connect')" +
                "   {" +
                "       inAckId = message.ext && message.ext.ack;" +
                "   }" +
                "   return message;" +
                "}," +
                "outgoing: function(message)" +
                "{" +
                "   if (message.channel == '/meta/connect')" +
                "   {" +
                "       outAckId = message.ext && message.ext.ack;" +
                "   }" +
                "   return message;" +
                "}" +
                "});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll
        Number outAckId = get("outAckId");
        // The server should have returned a non-negative value during the first connect call
        assert outAckId.intValue() >= 0;

        // Subscribe to receive server events
        evaluateScript("var msgCount = 0;");
        evaluateScript("$.cometd.subscribe('/echo', function(message) { ++msgCount; });");
        Thread.sleep(500); // Wait for the subscription
        // The server receives an event and sends it to the client via the long poll
        ackService.emit("test acknowledgement");
        Thread.sleep(500); // Wait for server-side event to arrive via long poll
        Number inAckId = get("inAckId");
        assert inAckId.intValue() > outAckId.intValue();
        Number msgCount = get("msgCount");
        assert msgCount.intValue() == 1;

        evaluateScript("$.cometd.unregisterExtension('test');");

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }

    private static class AckService extends BayeuxService
    {
        private AckService(Bayeux bayeux)
        {
            super(bayeux, "ack-test");
        }

        public void emit(String content)
        {
            getBayeux().getChannel("/echo", true).publish(getClient(), content, null);
        }
    }
}
