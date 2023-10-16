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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDAdviceTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testNoHandshakeAdviceAfterSessionExpired(String transport) throws Exception {
        initCometDServer(transport);

        // Removed handshake advices to make sure the client behaves well without them.
        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    message.remove(Message.ADVICE_FIELD);
                }
                return true;
            }
        });

        evaluateScript("const HandshakeListener = Java.type('" + HandshakeListener.class.getName() + "')");
        evaluateScript("const handshakeListener = new HandshakeListener();");
        HandshakeListener handshakeListener = javaScript.get("handshakeListener");
        handshakeListener.bayeuxServer = bayeuxServer;

        evaluateScript("const connectLatch = new Latch(1);");
        Latch connectLatch = javaScript.get("connectLatch");

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', cn => {
                    if (cn.successful) {
                        connectLatch.countDown();
                    }
                });
                cometd.handshake(hs => handshakeListener.handle(hs));
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Assertions.assertTrue(handshakeListener.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }

    public static class HandshakeListener {
        private final CountDownLatch latch = new CountDownLatch(1);
        private BayeuxServer bayeuxServer;
        private int handshakes;

        public void handle(Object jsMessage) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>)jsMessage;
            if ((Boolean)message.get("successful")) {
                ++handshakes;
                if (handshakes == 1) {
                    bayeuxServer.removeSession(bayeuxServer.getSession((String)message.get("clientId")));
                } else if (handshakes == 2) {
                    latch.countDown();
                }
            }
        }

        private boolean await(long time, TimeUnit unit) throws InterruptedException {
            return latch.await(time, unit);
        }
    }
}
