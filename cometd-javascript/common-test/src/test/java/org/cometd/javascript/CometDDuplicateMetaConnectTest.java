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
package org.cometd.javascript;

import org.junit.Test;

public class CometDDuplicateMetaConnectTest extends AbstractCometDLongPollingTest {
    @Test
    public void testDuplicateMetaConnectWithoutFailingExistingMetaConnect() throws Exception {
        defineClass(Latch.class);

        long backoff = 500;
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "'," +
                "logLevel: '" + getLogLevel() + "'," +
                "backoffIncrement: " + backoff +
                "});");

        evaluateScript("" +
                "var metaConnectIds = [];" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "  metaConnectIds.push(message.id);" +
                "});");

        evaluateScript("cometd.handshake();");

        // Wait for the /meta/connect to be held by the server.
        Thread.sleep(1000);

        // Simulate that a failed /meta/connect reply
        // arrives unexpectedly, it should be dropped.
        evaluateScript("cometd.receive({" +
                "  \"id\": \"0\"," +
                "  \"successful\": false," +
                "  \"channel\": \"/meta/connect\"" +
                "});");

        evaluateScript("window.assert(metaConnectIds.indexOf(\"0\") < 0);");

        disconnect();
    }
}
