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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDInitDisconnectTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testInitDisconnect(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const latch = new Latch(2);
                cometd.addListener('/**', () => latch.countDown());
                // Expect 2 messages: handshake and connect.
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        // Wait for the long poll to happen, so that we're
        // sure the disconnect is sent after the long poll.
        Thread.sleep(1000);

        String status = evaluateScript("cometd.getStatus();");
        Assertions.assertEquals("connected", status);

        // Expect disconnect and connect.
        latch.reset(2);
        disconnect();
        Assertions.assertTrue(latch.await(5000));

        status = evaluateScript("cometd.getStatus();");
        Assertions.assertEquals("disconnected", status);

        // Make sure there are no attempts to reconnect
        latch.reset(1);
        Assertions.assertFalse(latch.await(metaConnectPeriod * 3L));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHandshakeDisconnect(String transport) throws Exception {
        initCometDServer(transport);

        CountDownLatch removeLatch = new CountDownLatch(1);
        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    to.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());
                }
                return true;
            }
        });

        // Note that doing:
        //
        // cometd.handshake();
        // cometd.disconnect();
        //
        // will not work, since the disconnect will need to pass to the server
        // a clientId, which is not known since the handshake has not returned yet

        evaluateScript("""
                const disconnectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/handshake', message => {
                    if (message.successful) {
                        cometd.disconnect();
                    }
                });
                cometd.addListener('/meta/disconnect', () => disconnectLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        
        Assertions.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assertions.assertTrue(disconnectLatch.await(5000));
    }
}
