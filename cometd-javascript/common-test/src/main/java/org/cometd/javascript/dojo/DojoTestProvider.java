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
package org.cometd.javascript.dojo;

import java.net.URL;

import org.cometd.javascript.TestProvider;
import org.cometd.javascript.ThreadModel;

public class DojoTestProvider implements TestProvider {
    @Override
    public void provideCometD(ThreadModel threadModel, String fullContextURL) throws Exception {
        // Order of the script evaluation is important, as they depend one from the other
        threadModel.evaluate(new URL(fullContextURL + "/env.js"));
        // Rhino 1.7 puts the top Java packages in the root scope.
        // Unfortunately, "org" is also used as a JavaScript namespace,
        // so we need to remove the Java package to avoid clashes.
        threadModel.remove("org");
        threadModel.evaluate("window_location", "window.location = '" + fullContextURL + "'");
        String dojoBaseURL = "/js/dojo";
        threadModel.evaluate(new URL(fullContextURL + dojoBaseURL + "/dojo.js.uncompressed.js"));
        threadModel.evaluate("cometd", "" +
                "var cometdModule;" +
                "var cometd;" +
                "require({" +
                "    packages: [{name: 'cometd', location: '../cometd'}]," +
                "    baseUrl: '" + dojoBaseURL + "'" +
                "}, ['cometd/cometd', 'dojox/cometd'], function(m, c) {" +
                "    cometdModule = m;" +
                "    cometd = c; " +
                "});");
        threadModel.evaluate("original_transports", "" +
                "var originalTransports = {};" +
                "var transportNames = cometd.getTransportTypes();" +
                "for (var i = 0; i < transportNames.length; ++i)" +
                "{" +
                "    var transportName = transportNames[i];" +
                "    originalTransports[transportName] = cometd.findTransport(transportName);" +
                "}" +
                "");
        threadModel.evaluate("only_long_polling", "" +
                "cometd.unregisterTransports();" +
                "cometd.registerTransport('long-polling', originalTransports['long-polling']);");
    }

    @Override
    public void provideMessageAcknowledgeExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate("ack extension", "require(['dojox/cometd/ack']);");
    }

    @Override
    public void provideReloadExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate("reload extension", "require(['dojox/cometd/reload']);");
    }

    @Override
    public void provideTimestampExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate("timestamp extension", "require(['dojox/cometd/timestamp']);");
    }

    @Override
    public void provideTimesyncExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate("timesync extension", "require(['dojox/cometd/timesync']);");
    }

    @Override
    public void provideBinaryExtension(ThreadModel threadModel, String contextURL) throws Exception {
        threadModel.evaluate("binary extension", "require(['dojox/cometd/binary']);");
    }
}
