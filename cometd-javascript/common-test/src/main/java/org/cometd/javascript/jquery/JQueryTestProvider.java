package org.cometd.javascript.jquery;

import java.net.URL;

import org.cometd.javascript.TestProvider;
import org.cometd.javascript.ThreadModel;

public class JQueryTestProvider implements TestProvider
{
    public void provideCometD(ThreadModel threadModel, String contextURL) throws Exception
    {
        // Order of the script evaluation is important, as they depend one from the other
        threadModel.evaluate(new URL(contextURL + "/env.js"));
        threadModel.evaluate("window_location", "window.location = '" + contextURL + "'");
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery-1.5.1.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/json2.js"));
        threadModel.evaluate(new URL(contextURL + "/org/cometd.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cometd.js"));
        threadModel.evaluate("cometd", "var cometd = $.cometd;");
    }

    public void provideMessageAcknowledgeExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/AckExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cometd-ack.js"));
    }

    public void provideReloadExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cookie.js"));
        threadModel.evaluate(new URL(contextURL + "/org/cometd/ReloadExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cometd-reload.js"));
    }

    public void provideTimestampExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/TimeStampExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cometd-timestamp.js"));
    }

    public void provideTimesyncExtension(ThreadModel threadModel, String contextURL) throws Exception
    {
        threadModel.evaluate(new URL(contextURL + "/org/cometd/TimeSyncExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/jquery/jquery.cometd-timesync.js"));
    }
}
