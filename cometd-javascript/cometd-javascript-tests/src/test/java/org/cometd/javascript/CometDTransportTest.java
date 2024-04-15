/*
 * Copyright (c) 2008 the original author or authors.
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

import java.net.URL;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDTransportTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testTransport(String transport) throws Exception {
        initCometDServer(transport);

        URL resource = getClass().getResource("/js/cometd/RequestTransport.js");
        evaluateScript("LocalTransport.mjs", """
                import {RequestTransport} from '$P';
                
                globalThis.LocalTransport = class extends RequestTransport {
                    #sends = 0;
                    #readyLatch;

                    constructor(readyLatch) {
                        super();
                        this.#readyLatch = readyLatch;
                    }

                    getSends() { return this.#sends; };

                    accept() {
                        return true;
                    };

                    transportSend(envelope, request) {
                        ++this.#sends;
                        let response;
                        let timeout;
                        switch (this.#sends) {
                            case 1:
                                response = JSON.stringify([{
                                    "successful": true,
                                    "channel": "/meta/handshake",
                                    "clientId": "dmigjcjnakuysa9j29",
                                    "id": "1",
                                    "minimumVersion": "0.9",
                                    "version": "1.0",
                                    "supportedConnectionTypes": ["long-polling","callback-polling"],
                                    "advice": {
                                        "reconnect": "retry",
                                        "interval": 0,
                                        "timeout": 5000
                                    }
                                }]);
                                timeout = 0;
                                break;
                            case 2:
                                response = JSON.stringify([{
                                    "successful": true,
                                    "channel": "/meta/connect",
                                    "id": "2",
                                    "advice": {
                                        "reconnect": "retry",
                                        "interval": 0,
                                        "timeout": 5000
                                    }
                                }]);
                                timeout = 0;
                                break;
                            case 3:
                                response = JSON.stringify([{
                                    "successful": true,
                                    "channel": "/meta/connect",
                                    "id": "3",
                                    "advice": {
                                        "reconnect": "retry",
                                        "interval": 0,
                                        "timeout": 5000
                                    }
                                }]);
                                timeout = 5000;
                                this.#readyLatch.countDown();
                                break;
                            case 4:
                                response = JSON.stringify([{
                                    "successful": true,
                                    "channel": "/meta/disconnect",
                                    "id": "4"
                                }]);
                                timeout = 0;
                                break;
                            default:
                                throw 'Test Error';
                        }

                        // Respond asynchronously.
                        cometd.setTimeout(() => {
                            this.transportSuccess(envelope, request, this.convertToMessages(response));
                        }, timeout);
                    }
                }
                """.replace("$P", resource.getPath()));

        evaluateScript("""
                const readyLatch = new Latch(1);
                const localTransport = new LocalTransport(readyLatch);

                cometd.unregisterTransports();
                // The server does not support a 'local' transport, so use 'long-polling'.
                const registered = cometd.registerTransport('long-polling', localTransport);
                window.assert(registered === true, 'local transport not registered');

                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch readyLatch = javaScript.get("readyLatch");
        Assertions.assertTrue(readyLatch.await(5000));

        Assertions.assertEquals(3, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assertions.assertEquals("connected", evaluateScript("cometd.getStatus();"));

        readyLatch.reset(1);
        evaluateScript("cometd.disconnect(() => readyLatch.countDown());");
        Assertions.assertTrue(readyLatch.await(5000));

        Assertions.assertEquals(4, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assertions.assertEquals("disconnected", evaluateScript("cometd.getStatus();"));
    }
}
