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

import org.cometd.javascript.AbstractCometDTest;

public abstract class AbstractCometDDojoTest extends AbstractCometDTest {
    @Override
    protected void provideCometD() throws Exception {
        String dojoBaseURL = "/js/dojo";
        javaScript.evaluate(getClass().getResource(dojoBaseURL + "/dojo.js.uncompressed.js"));
        evaluateScript("cometd", "" +
                "var cometdModule;" +
                "var cometd;" +
                "var originalTransports = {};" +
                "require({" +
                "    packages: [{name: 'cometd', location: '../cometd'}]," +
                "    baseUrl: '" + dojoBaseURL + "'" +
                "}, ['cometd/cometd', 'dojox/cometd'], function(m, c) {" +
                "    cometdModule = m;" +
                "    cometd = c; " +
                "    var transportNames = cometd.getTransportTypes();" +
                "    for (var i = 0; i < transportNames.length; ++i) {" +
                "        var transportName = transportNames[i];" +
                "        originalTransports[transportName] = cometd.findTransport(transportName);" +
                "    }" +
                "});");
    }
}
