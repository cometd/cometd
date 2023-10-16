/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractCometDDojoTest extends AbstractCometDTest {
    @BeforeEach
    public void init() throws Exception {
        initCometDServer(null);
    }

    @Override
    protected void provideCometD(String transport) {
        String dojoBaseURL = "/js/dojo";
        javaScript.evaluate(getClass().getResource(dojoBaseURL + "/dojo.js.uncompressed.js"));
        evaluateScript("cometd", """
                let cometdModule;
                let cometd;
                const originalTransports = {};
                require({
                    packages: [{name: 'cometd', location: '../cometd'}],
                    baseUrl: '$U'
                }, ['cometd/cometd', 'dojox/cometd'], (m, c) => {
                    cometdModule = m;
                    cometd = c;
                    const transportNames = cometd.getTransportTypes();
                    for (let i = 0; i < transportNames.length; ++i) {
                        const transportName = transportNames[i];
                        originalTransports[transportName] = cometd.findTransport(transportName);
                    }
                });
                """.replace("$U", dojoBaseURL));
        if (transport != null) {
            evaluateScript("only_" + transport, """
                    cometd.unregisterTransports();
                    cometd.registerTransport('$T', originalTransports['$T']);
                    """.replace("$T", transport));
        }
    }
}
