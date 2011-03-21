package org.cometd.javascript.dojo;

import java.net.URL;

import org.cometd.javascript.TestProvider;
import org.cometd.javascript.ThreadModel;

public class DojoTestProvider implements TestProvider
{
    public void provideCometD(ThreadModel threadModel, String contextURL) throws Exception
    {
        // Order of the script evaluation is important, as they depend one from the other
        threadModel.evaluate(new URL(contextURL + "/env.js"));
        threadModel.evaluate("window_location", "window.location = '" + contextURL + "'");
        threadModel.evaluate(new URL(contextURL + "/dojo/dojo.js.uncompressed.js"));
        threadModel.evaluate(new URL(contextURL + "/dojo/io/script.js"));
        threadModel.evaluate(new URL(contextURL + "/org/cometd.js"));
        threadModel.evaluate(new URL(contextURL + "/dojox/cometd.js"));
        threadModel.evaluate("cometd", "var cometd = dojox.cometd;");
    }

    public void provideMessageAcknowledgeExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/AckExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/dojox/cometd/ack.js"));
    }

    public void provideReloadExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        // dojo.regexp is required by dojo.cookie
        threadModel.evaluate(new URL(contextURL + "/dojo/regexp.js"));
        threadModel.evaluate(new URL(contextURL + "/dojo/cookie.js"));
        threadModel.evaluate(new URL(contextURL + "/org/cometd/ReloadExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/dojox/cometd/reload.js"));
    }

    public void provideTimestampExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/TimeStampExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/dojox/cometd/timestamp.js"));
    }

    public void provideTimesyncExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/TimeSyncExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/dojox/cometd/timesync.js"));
    }
}
