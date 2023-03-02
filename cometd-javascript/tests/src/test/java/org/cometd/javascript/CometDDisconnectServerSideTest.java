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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDDisconnectServerSideTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testServerSideDisconnect(String transport) throws Exception {
        initCometDServer(transport);

        CountDownLatch connectRequestLatch = new CountDownLatch(1);
        ServerSideDisconnectService service = new ServerSideDisconnectService(bayeuxServer, connectRequestLatch);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const connectLatch = new Latch(2);
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                const disconnectLatch = new Latch(1);
                cometd.addListener('/meta/disconnect', () => disconnectLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Assertions.assertTrue(connectRequestLatch.await(5, TimeUnit.SECONDS));

        service.disconnect(evaluateScript("cometd.getClientId()"));

        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assertions.assertTrue(disconnectLatch.await(5000));
        Latch connectResponseLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectResponseLatch.await(5000));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDeliverAndServerSideDisconnect(String transport) throws Exception {
        initCometDServer(transport);

        String channelName = "/service/kick";
        CountDownLatch connectLatch = new CountDownLatch(1);
        DeliverAndServerSideDisconnectService service = new DeliverAndServerSideDisconnectService(bayeuxServer, channelName, connectLatch);

        evaluateScript("""
                const deliverLatch = new Latch(1);
                const disconnectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/disconnect', () => disconnectLatch.countDown());
                cometd.addListener('/meta/handshake', message => {
                    if (message.successful) {
                        cometd.addListener('$C', () => deliverLatch.countDown());
                    }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$C", channelName));

        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        service.kick(evaluateScript("cometd.getClientId()"));

        Latch deliverLatch = javaScript.get("deliverLatch");
        Assertions.assertTrue(deliverLatch.await(5000));
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        Assertions.assertTrue(disconnectLatch.await(5000));
    }

    public static class ServerSideDisconnectService extends AbstractService {
        private final AtomicInteger connects = new AtomicInteger();
        private final CountDownLatch connectRequestLatch;

        ServerSideDisconnectService(BayeuxServer bayeuxServer, CountDownLatch connectRequestLatch) {
            super(bayeuxServer, "test_server_side_disconnect");
            this.connectRequestLatch = connectRequestLatch;
            addService(Channel.META_CONNECT, "processMetaConnect");
        }

        public void processMetaConnect(ServerSession session, ServerMessage message) {
            if (connects.incrementAndGet() == 2) {
                connectRequestLatch.countDown();
            }
        }

        public void disconnect(String sessionId) {
            ServerSession session = getBayeux().getSession(sessionId);
            session.disconnect();
        }
    }

    public static class DeliverAndServerSideDisconnectService extends AbstractService {
        private final AtomicInteger connects = new AtomicInteger();
        private final String channelName;
        private final CountDownLatch connectLatch;

        DeliverAndServerSideDisconnectService(BayeuxServer bayeuxServer, String channelName, CountDownLatch connectLatch) {
            super(bayeuxServer, "test_disconnect_with_messages");
            this.channelName = channelName;
            this.connectLatch = connectLatch;
            addService(Channel.META_CONNECT, "processMetaConnect");
        }

        public void processMetaConnect(ServerSession session, ServerMessage message) {
            if (connects.incrementAndGet() == 2) {
                connectLatch.countDown();
            }
        }

        public void kick(String sessionId) {
            ServerMessage.Mutable kickMessage = getBayeux().newMessage();
            kickMessage.setChannel(channelName);
            kickMessage.setData(new HashMap<>());

            ServerSession session = getBayeux().getSession(sessionId);

            // We need to batch otherwise the deliver() will wake up the long poll
            // and the disconnect may not be delivered, since the client won't issue
            // a new long poll, and the disconnect will remain in the queue
            session.batch(() -> {
                session.deliver(getServerSession(), kickMessage, Promise.noop());
                session.disconnect();
            });
        }
    }
}
