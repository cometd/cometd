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
package org.cometd.javascript.extension;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractService;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDAckAndReloadExtensionsTest extends AbstractCometDTransportsTest {
    @Override
    public void initCometDServer(String transport) throws Exception {
        super.initCometDServer(transport);
        initExtensions();
    }

    private void initExtensions() {
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        provideMessageAcknowledgeExtension();
        provideReloadExtension();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAckAndReloadExtensions(String transport) throws Exception {
        initCometDServer(transport);

        AckService ackService = new AckService(bayeuxServer);

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Send a message so that the ack counter is initialized
        evaluateScript("const latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("""
                cometd.subscribe('/test', () => latch.countDown());
                cometd.publish('/test', 'message1');
                """);
        Assertions.assertTrue(latch.await(5000));

        // Wait to allow the long poll to go to the server and tell it the ack id
        Thread.sleep(1000);

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page, and simulate that a message has been received meanwhile on server
        destroyPage();
        ackService.emit("message2");
        initPage(transport);
        initExtensions();

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        // Expect 2 messages: one sent in the middle of reload, one after reload
        evaluateScript("const latch = new Latch(2);");
        latch = javaScript.get("latch");
        evaluateScript("""
                const testMessage = [];
                cometd.addListener('/meta/handshake', () => {
                   cometd.batch(() => {
                       cometd.subscribe('/test', message => { testMessage.push(message); latch.countDown(); });
                       cometd.subscribe('/echo', () => readyLatch.countDown());
                       cometd.publish('/echo', {});
                   });
                });
                cometd.handshake();
                """);
        Assertions.assertTrue(readyLatch.await(5000));

        ackService.emit("message3");
        Assertions.assertTrue(latch.await(5000));

        evaluateScript("window.assert(testMessage.length === 2, 'testMessage.length');");
        evaluateScript("window.assert(testMessage[0].data === 'message2', 'message2');");
        evaluateScript("window.assert(testMessage[1].data === 'message3', 'message3');");

        disconnect();
    }

    public static class AckService extends AbstractService {
        private AckService(BayeuxServer bayeux) {
            super(bayeux, "ack-test");
        }

        public void emit(String content) {
            getBayeux().getChannel("/test").publish(getServerSession(), content, Promise.noop());
        }
    }
}
