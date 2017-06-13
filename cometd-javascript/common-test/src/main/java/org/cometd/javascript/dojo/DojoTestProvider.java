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
package org.cometd.javascript.dojo;

import java.net.URL;

import org.cometd.javascript.JavaScript;
import org.cometd.javascript.TestProvider;

public class DojoTestProvider implements TestProvider {
    @Override
    public void provideCometD(JavaScript javaScript, String fullContextURL) throws Exception {
        String dojoBaseURL = "/js/dojo";
        javaScript.evaluate(new URL(fullContextURL + dojoBaseURL + "/dojo.js.uncompressed.js"));
        javaScript.evaluate("cometd", "" +
                "var cometdModule;" +
                "var cometd;" +
                "require({" +
                "    packages: [{name: 'cometd', location: '../cometd'}]," +
                "    baseUrl: '" + dojoBaseURL + "'" +
                "}, ['cometd/cometd', 'dojox/cometd'], function(m, c) {" +
                "    cometdModule = m;" +
                "    cometd = c; " +
                "});");
        javaScript.evaluate("original_transports", "" +
                "var originalTransports = {};" +
                "var transportNames = cometd.getTransportTypes();" +
                "for (var i = 0; i < transportNames.length; ++i)" +
                "{" +
                "    var transportName = transportNames[i];" +
                "    originalTransports[transportName] = cometd.findTransport(transportName);" +
                "}" +
                "");
        javaScript.evaluate("only_long_polling", "" +
                "cometd.unregisterTransports();" +
                "cometd.registerTransport('long-polling', originalTransports['long-polling']);");
    }

    @Override
    public void provideMessageAcknowledgeExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate("ack extension", "require(['dojox/cometd/ack']);");
    }

    @Override
    public void provideReloadExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate("reload extension", "require(['dojox/cometd/reload']);");
    }

    @Override
    public void provideTimestampExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate("timestamp extension", "require(['dojox/cometd/timestamp']);");
    }

    @Override
    public void provideTimesyncExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate("timesync extension", "require(['dojox/cometd/timesync']);");
    }

    @Override
    public void provideBinaryExtension(JavaScript javaScript, String contextURL) throws Exception {
        javaScript.evaluate("binary extension", "require(['dojox/cometd/binary']);");
    }
}
