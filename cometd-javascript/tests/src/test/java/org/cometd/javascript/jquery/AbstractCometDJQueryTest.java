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
package org.cometd.javascript.jquery;

import org.cometd.javascript.AbstractCometDTest;
import org.junit.jupiter.api.BeforeEach;

public class AbstractCometDJQueryTest extends AbstractCometDTest {
    @BeforeEach
    public void init() throws Exception {
        initCometDServer(null);
    }

    @Override
    protected void provideCometD(String transport) {
        javaScript.evaluate(getClass().getResource("/js/jquery/jquery-3.6.0.js"));
        javaScript.evaluate(getClass().getResource("/js/cometd/cometd.js"));
        javaScript.evaluate(getClass().getResource("/js/jquery/jquery.cometd.js"));
        evaluateScript("cometd", """
                const cometdModule = org.cometd;
                const cometd = $.cometd;
                const originalTransports = {};
                const transportNames = cometd.getTransportTypes();
                for (let i = 0; i < transportNames.length; ++i) {
                    const transportName = transportNames[i];
                    originalTransports[transportName] = cometd.findTransport(transportName);
                }
                """);
        if (transport != null) {
            evaluateScript("only_" + transport, """
                    cometd.unregisterTransports();
                    cometd.registerTransport('$T', originalTransports['$T']);
                    """.replace("$T", transport));
        }
    }
}
