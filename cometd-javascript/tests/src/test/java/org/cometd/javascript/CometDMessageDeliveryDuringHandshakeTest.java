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

        CountDownLatch serverMessagesLatch = new CountDownLatch(1);
        Consumer<String> checkQueue = clientId -> {
            ServerSessionImpl serverSession = (ServerSessionImpl)bayeuxServer.getSession(clientId);
            if (serverSession.getQueue().isEmpty() == allowHandshakeMessages)
                serverMessagesLatch.countDown();
        };
        javaScript.put("checkQueue", checkQueue);

        evaluateScript("" +
                "cometd.configure({" +
                "    url: '" + cometdURL + "', " +
                "    logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("var clientMessagesLatch = new Latch(1);");
        Latch clientMessagesLatch = javaScript.get("clientMessagesLatch");
        evaluateScript("" +
                "var messages = [];" +
                "var listener = function(message) {" +
                "    messages.push(message);" +
                "    if (messages.length === 4) {" +
                "        clientMessagesLatch.countDown();" +
                "    }" +
                "};");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', listener);" +
                "cometd.addListener('" + channelName + "', listener);" +
                "cometd.addListener('/meta/connect', listener);" +
                "" +
                "cometd.addListener('/meta/handshake', function(m) {" +
                "    checkQueue.accept(m.clientId);" +
                "})");
        evaluateScript("" +
                "cometd.handshake();");

        Assertions.assertTrue(serverMessagesLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientMessagesLatch.await(5000));

        evaluateScript("window.assert(messages[0].channel === '/meta/handshake', 'not handshake' + JSON.stringify(messages[0]));");
        evaluateScript("window.assert(messages[1].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[1]));");
        evaluateScript("window.assert(messages[2].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[2]));");
        evaluateScript("window.assert(messages[3].channel === '/meta/connect', 'not connect: ' + JSON.stringify(messages[3]));");
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

        evaluateScript("var clientMessagesLatch = new Latch(1);");
        Latch clientMessagesLatch = javaScript.get("clientMessagesLatch");
        evaluateScript("" +
                "var messages = [];" +
                "var listener = function(message) {" +
                "    messages.push(message);" +
                "    if (messages.length === 3) {" +
                "        clientMessagesLatch.countDown();" +
                "    }" +
                "};");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', listener);" +
                "cometd.addListener('" + channelName + "', listener);" +
                "cometd.addListener('/meta/connect', listener);");
        evaluateScript("" +
                "cometd.handshake();");

        Assertions.assertTrue(clientMessagesLatch.await(5000));

        evaluateScript("window.assert(messages[0].channel === '/meta/handshake', 'not handshake' + JSON.stringify(messages[0]));");
        evaluateScript("window.assert(messages[1].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[1]));");
        evaluateScript("window.assert(messages[2].channel === '/meta/connect', 'not connect: ' + JSON.stringify(messages[2]));");
    }
}
