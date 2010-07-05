package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.javascript.Latch;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.TimesyncExtension;

/**
 * @version $Revision$ $Date$
 */
public class CometdTimeSyncExtensionTest extends AbstractCometdJQueryTest
{
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addExtension(new TimesyncExtension());
    }

    public void testTimeSync() throws Exception
    {
        URL timesyncExtensionURL = new URL(contextURL + "/org/cometd/TimeSyncExtension.js");
        evaluateURL(timesyncExtensionURL);
        URL jqueryTimesyncExtensionURL = new URL(contextURL + "/jquery/jquery.cometd-timesync.js");
        evaluateURL(jqueryTimesyncExtensionURL);

        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");

        evaluateScript("var inTimeSync = undefined;");
        evaluateScript("var outTimeSync = undefined;");
        evaluateScript("$.cometd.registerExtension('test', {" +
                "incoming: function(message)" +
                "{" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') == 0)" +
                "    {" +
                "        /* The timesync from the server may be missing if it's accurate enough */" +
                "        var timesync = message.ext && message.ext.timesync;" +
                "        if (timesync) inTimeSync = timesync;" +
                "    }" +
                "    return message;" +
                "}," +
                "outgoing: function(message)" +
                "{" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') == 0)" +
                "    {" +
                "        outTimeSync = message.ext && message.ext.timesync;" +
                "    }" +
                "    return message;" +
                "}" +
                "});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/handshake', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        // Both client and server should support timesync
        Object outTimeSync = get("outTimeSync");
        assertNotNull(outTimeSync);
        Object inTimeSync = get("inTimeSync");
        assertNotNull(inTimeSync);

        evaluateScript("var timesync = $.cometd.getExtension('timesync');");
        evaluateScript("var networkLag = timesync.getNetworkLag();");
        evaluateScript("var timeOffset = timesync.getTimeOffset();");
        int networkLag = ((Number)get("networkLag")).intValue();
        assertTrue(networkLag > 0);

        evaluateScript("$.cometd.disconnect(true);");
    }
}
