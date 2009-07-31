package org.cometd.javascript.jquery.extension;

import java.net.URL;

import org.cometd.Bayeux;
import org.cometd.javascript.jquery.AbstractCometdJQueryTest;
import org.cometd.server.ext.TimesyncExtension;
import org.testng.annotations.Test;

/**
 * @version $Revision$ $Date$
 */
public class CometdTimeSyncExtensionTest extends AbstractCometdJQueryTest
{
    protected void customizeBayeux(Bayeux bayeux)
    {
        bayeux.addExtension(new TimesyncExtension());
    }

    @Test
    public void testTimeSync() throws Exception
    {
        URL timesyncExtensionURL = new URL(contextURL + "/org/cometd/TimeSyncExtension.js");
        evaluateURL(timesyncExtensionURL);

        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        evaluateScript("$.cometd.registerExtension('timesync', new org.cometd.TimeSyncExtension());");

        evaluateScript("var inTimeSync = undefined;");
        evaluateScript("var outTimeSync = undefined;");
        evaluateScript("$.cometd.registerExtension('test', {" +
                "incoming: function(message)" +
                "{" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') == 0)" +
                "    {" +
                "        inTimeSync = message.ext && message.ext.timesync;" +
                "    }" +
                "   return message;" +
                "}," +
                "outgoing: function(message)" +
                "{" +
                "    var channel = message.channel;" +
                "    if (channel && channel.indexOf('/meta/') == 0)" +
                "    {" +
                "        outTimeSync = message.ext && message.ext.timesync;" +
                "    }" +
                "   return message;" +
                "}" +
                "});");
        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500); // Wait for the long poll

        // Both client and server should support timesync
        Object outTimeSync = get("outTimeSync");
        assert outTimeSync != null;
        Object inTimeSync = get("inTimeSync");
        assert inTimeSync != null;

        evaluateScript("var timesync = $.cometd.getExtension('timesync');");
        evaluateScript("var networkLag = timesync.getNetworkLag();");
        evaluateScript("var timeOffset = timesync.getTimeOffset();");
        int networkLag = ((Number)get("networkLag")).intValue();
        assert networkLag > 0;

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }
}
