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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDMessageDeliveryDuringHandshakeTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesNotSentInHandshakeResponse(String transport) throws Exception {
        initCometDServer(transport);
        testMessagesInHandshakeResponse(false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponse(String transport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(transport, options);
        testMessagesInHandshakeResponse(true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtension(String transport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(transport, options);
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        provideMessageAcknowledgeExtension();
        testMessagesInHandshakeResponse(true);
    }

    private void testMessagesInHandshakeResponse(boolean allowHandshakeMessages) throws Exception {
        String channelName = "/test";
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }
        });

        CountDownLatch queueLatch = new CountDownLatch(1);
        Consumer<String> checkQueue = clientId -> {
            ServerSessionImpl serverSession = (ServerSessionImpl)bayeuxServer.getSession(clientId);
            if (serverSession.getQueue().isEmpty() == allowHandshakeMessages) {
                queueLatch.countDown();
            }
        };
        javaScript.put("checkQueue", checkQueue);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const handshakeLatch = new Latch(1);
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                const channelLatch = new Latch(2);
                cometd.addListener('$C', () => channelLatch.countDown());
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.addListener('/meta/handshake', m => checkQueue.accept(m.clientId));
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$C", channelName));

        Assertions.assertTrue(queueLatch.await(5, TimeUnit.SECONDS));
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch channelLatch = javaScript.get("channelLatch");
        Assertions.assertTrue(channelLatch.await(5000));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesSentInHandshakeResponseWithAckExtensionWithDeQueueListener(String transport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(transport, options);
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                session.addListener((ServerSession.DeQueueListener)(s, queue) -> {
                    while (queue.size() > 1) {
                        queue.poll();
                    }
                });
            }
        });
        provideMessageAcknowledgeExtension();

        String channelName = "/test";
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }
        });

        evaluateScript("" +
                "cometd.configure({" +
                "    url: '" + cometdURL + "', " +
                "    logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const handshakeLatch = new Latch(1);
                const channelLatch = new Latch(1);
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                cometd.addListener('$C', () => channelLatch.countDown());
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$C", channelName));

        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Latch channelLatch = javaScript.get("channelLatch");
        Assertions.assertTrue(channelLatch.await(5000));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        disconnect();
    }
}
