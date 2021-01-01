/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDTransportTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testTransport(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("cometd.unregisterTransports();");

        String localTransport =
                "var readyLatch = new Latch(1);" +
                        "function LocalTransport()" +
                        "{" +
                        "    var _super = new cometdModule.RequestTransport();" +
                        "    var that = cometdModule.Transport.derive(_super);" +
                        "    var _sends = 0;" +
                        "" +
                        "    that.getSends = function() { return _sends; };" +
                        "" +
                        "    that.accept = function(version, crossDomain) {" +
                        "        return true;" +
                        "    };" +
                        "" +
                        "    that.transportSend = function(envelope, request) {" +
                        "        ++_sends;" +
                        "        var response;" +
                        "        var timeout;" +
                        "        switch (_sends) {" +
                        "            case 1:" +
                        "                response = '[{" +
                        "                    \"successful\":true," +
                        "                    \"channel\":\"/meta/handshake\"," +
                        "                    \"clientId\":\"dmigjcjnakuysa9j29\"," +
                        "                    \"id\":\"1\"," +
                        "                    \"minimumVersion\":\"0.9\"," +
                        "                    \"version\":\"1.0\"," +
                        "                    \"supportedConnectionTypes\":[\"long-polling\",\"callback-polling\"]," +
                        "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                        "                }]';" +
                        "                timeout = 0;" +
                        "                break;" +
                        "            case 2:" +
                        "                response = '[{" +
                        "                    \"successful\":true," +
                        "                    \"channel\":\"/meta/connect\"," +
                        "                    \"id\":\"2\"," +
                        "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                        "                }]';" +
                        "                timeout = 0;" +
                        "                break;" +
                        "            case 3:" +
                        "                response = '[{" +
                        "                    \"successful\":true," +
                        "                    \"channel\":\"/meta/connect\"," +
                        "                    \"id\":\"2\"," +
                        "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                        "                }]';" +
                        "                timeout = 5000;" +
                        "                readyLatch.countDown();" +
                        "                break;" +
                        "            case 4:" +
                        "                response = '[{" +
                        "                    \"successful\":true," +
                        "                    \"channel\":\"/meta/disconnect\"," +
                        "                    \"id\":\"3\"" +
                        "                }]';" +
                        "                timeout = 0;" +
                        "                break;" +
                        "            default:" +
                        "                throw 'Test Error';" +
                        "        }" +
                        "" +
                        "        /* Respond asynchronously */" +
                        "        var self = this;" +
                        "        setTimeout(function() {" +
                        "            self.transportSuccess(envelope, request, self.convertToMessages(response));" +
                        "        }, timeout);" +
                        "    };" +
                        "" +
                        "    return that;" +
                        "};";

        evaluateScript(localTransport);

        evaluateScript("var localTransport = new LocalTransport();");
        // The server does not support a 'local' transport, so use 'long-polling'
        boolean registered = evaluateScript("cometd.registerTransport('long-polling', localTransport);");
        Assertions.assertTrue(registered);

        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(readyLatch.await(5000));

        Assertions.assertEquals(3, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assertions.assertEquals("connected", evaluateScript("cometd.getStatus();"));

        readyLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(readyLatch.await(5000));

        Assertions.assertEquals(4, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assertions.assertEquals("disconnected", evaluateScript("cometd.getStatus();"));
    }
}
