/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.cometd.javascript.TestProvider;
import org.cometd.javascript.ThreadModel;

public class JQueryTestProvider implements TestProvider {
    @Override
    public void provideCometD(ThreadModel threadModel, String contextURL) throws Exception {
        // Order of the script evaluation is important, as they depend one from the other
        threadModel.evaluate(new URL(contextURL + "/json2.js"));
        threadModel.evaluate(new URL(contextURL + "/env.js"));
        // Rhino 1.7 puts the top Java packages in the root scope.
        // Unfortunately, "org" is also used as a JavaScript namespace,
        // so we need to remove the Java package to avoid clashes.
        threadModel.remove("org");
        threadModel.evaluate("window_location", "window.location = '" + contextURL + "'");
        threadModel.evaluate(new URL("https://code.jquery.com/jquery-3.2.1.js"));
        threadModel.evaluate(new URL(contextURL + "/js/cometd/cometd.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd.js"));
        threadModel.evaluate("cometdModule", "var cometdModule = org.cometd;");
        threadModel.evaluate("cometd", "var cometd = $.cometd;");
        threadModel.evaluate("original_transports", "" +
                "var originalTransports = {};" +
                "var transportNames = cometd.getTransportTypes();" +
                "for (var i = 0; i < transportNames.length; ++i)" +
                "{" +
                "    var transportName = transportNames[i];" +
                "    originalTransports[transportName] = cometd.findTransport(transportName);" +
                "}" +
                "");
        threadModel.evaluate("only_websocket", "" +
                "cometd.unregisterTransports();" +
                "cometd.registerTransport('websocket', originalTransports['websocket']);");
    }

    @Override
    public void provideMessageAcknowledgeExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate(new URL(contextURL + "/js/cometd/AckExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-ack.js"));
    }

    @Override
    public void provideReloadExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate(new URL(contextURL + "/js/cometd/ReloadExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-reload.js"));
    }

    @Override
    public void provideTimestampExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate(new URL(contextURL + "/js/cometd/TimeStampExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-timestamp.js"));
    }

    @Override
    public void provideTimesyncExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate(new URL(contextURL + "/js/cometd/TimeSyncExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-timesync.js"));
    }

    @Override
    public void provideBinaryExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate(new URL(contextURL + "/js/cometd/BinaryExtension.js"));
        threadModel.evaluate(new URL(contextURL + "/js/jquery/jquery.cometd-binary.js"));
    }
}
