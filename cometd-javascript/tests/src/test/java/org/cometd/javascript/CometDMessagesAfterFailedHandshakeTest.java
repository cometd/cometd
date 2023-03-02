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
package org.cometd.javascript;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDMessagesAfterFailedHandshakeTest extends AbstractCometDTransportsTest {
    @Override
    public void initCometDServer(String transport) throws Exception {
        super.initCometDServer(transport);
        bayeuxServer.setSecurityPolicy(new Policy());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscribeAfterFailedHandshake(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const handshakeLatch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                    if (!message.successful) {
                        cometd.subscribe('/foo', () => {});
                        handshakeLatch.countDown();
                    }
                });
                const subscribeLatch = new Latch(1);
                cometd.addListener('/meta/subscribe', message => {
                    if (!message.successful) {
                        subscribeLatch.countDown();
                    }
                });
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishAfterFailedHandshake(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const handshakeLatch = new Latch(1);
                const publishLatch = new Latch(1);
                cometd.addListener('/meta/handshake', message => {
                    if (!message.successful) {
                        cometd.publish('/foo', {});
                        handshakeLatch.countDown();
                    }
                });
                cometd.addListener('/meta/publish', message => {
                    if (!message.successful) {
                        publishLatch.countDown();
                    }
                });
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch publishLatch = javaScript.get("publishLatch");
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    private static class Policy extends DefaultSecurityPolicy {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
            return false;
        }
    }
}
