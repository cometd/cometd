/*
 * Copyright (c) 2008-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.javascript.jquery;

import java.net.URL;

import org.cometd.javascript.JavaScript;
import org.cometd.javascript.TestProvider;

public class JQueryTestProvider implements TestProvider {
    @Override
    public void provideCometD(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/jquery-3.2.1.js"));
        javaScript.evaluate(new URL(contextURL + "/js/cometd/cometd.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd.js"));
        javaScript.evaluate("cometdModule", "var cometdModule = org.cometd;");
        javaScript.evaluate("cometd", "var cometd = $.cometd;");
        javaScript.evaluate("original_transports", "" +
                "var originalTransports = {};" +
                "var transportNames = cometd.getTransportTypes();" +
                "for (var i = 0; i < transportNames.length; ++i)" +
                "{" +
                "    var transportName = transportNames[i];" +
                "    originalTransports[transportName] = cometd.findTransport(transportName);" +
                "}" +
                "");
        javaScript.evaluate("only_websocket", "" +
                "cometd.unregisterTransports();" +
                "cometd.registerTransport('websocket', originalTransports['websocket']);");
    }

    @Override
    public void provideMessageAcknowledgeExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/js/cometd/AckExtension.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-ack.js"));
    }

    @Override
    public void provideReloadExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/js/cometd/ReloadExtension.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-reload.js"));
    }

    @Override
    public void provideTimestampExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/js/cometd/TimeStampExtension.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-timestamp.js"));
    }

    @Override
    public void provideTimesyncExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/js/cometd/TimeSyncExtension.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-timesync.js"));
    }

    @Override
    public void provideBinaryExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate(new URL(contextURL + "/js/cometd/BinaryExtension.js"));
        javaScript.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-binary.js"));
    }
}
