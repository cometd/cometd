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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDConnectTemporaryFailureTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectTemporaryFailure(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new DeleteMetaConnectExtension());

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const handshakeLatch = new Latch(1);
                const connectLatch = new Latch(1);
                const failureLatch = new Latch(1);
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                let wasConnected = false;
                let connected = false;
                cometd.addListener('/meta/connect', message => {
                   window.console.debug('metaConnect: was', wasConnected, 'is', connected, 'message', message.successful);
                   wasConnected = connected;
                   connected = message.successful === true;
                   if (!wasConnected && connected) {
                       connectLatch.countDown();
                   } else if (wasConnected && !connected) {
                       failureLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));
        Latch failureLatch = javaScript.get("failureLatch");
        Assertions.assertEquals(1L, failureLatch.getCount());

        handshakeLatch.reset(1);
        connectLatch.reset(1);
        // Wait for the /meta/connect to temporarily fail.
        Assertions.assertTrue(failureLatch.await(metaConnectPeriod * 2L));
        Assertions.assertEquals(1L, handshakeLatch.getCount());
        Assertions.assertEquals(1L, connectLatch.getCount());

        // Implementation will backoff the /meta/connect attempt.
        long backoff = ((Number)evaluateScript("cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(backoff);

        failureLatch.reset(1);
        // Reconnection will trigger /meta/connect.
        Assertions.assertTrue(connectLatch.await(5000));
        Assertions.assertEquals(1L, handshakeLatch.getCount());
        Assertions.assertEquals(1L, failureLatch.getCount());

        disconnect();
    }

    private static class DeleteMetaConnectExtension implements BayeuxServer.Extension {
        private int connects;

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                ++connects;
                return connects != 3;
            }
            return true;
        }
    }
}
