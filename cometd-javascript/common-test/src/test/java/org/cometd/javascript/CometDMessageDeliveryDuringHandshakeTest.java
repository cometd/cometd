/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Assert;
import org.junit.Test;

public class CometDMessageDeliveryDuringHandshakeTest extends AbstractCometDTransportsTest {
    @Override
    public void initCometDServer() throws Exception {
    }

    @Test
    public void testMessagesNotSentInHandshakeResponse() throws Exception {
        Map<String, String> options = new HashMap<>();
        initCometDServer(options);
        testMessagesInHandshakeResponse(false);
    }

    @Test
    public void testMessagesSentInHandshakeResponse() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(options);
        testMessagesInHandshakeResponse(true);
    }

    @Test
    public void testMessagesSentInHandshakeResponseWithAckExtension() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(options);
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        provideMessageAcknowledgeExtension();
        testMessagesInHandshakeResponse(true);
    }

    private void testMessagesInHandshakeResponse(final boolean allowHandshakeMessages) throws Exception {
        final String channelName = "/test";
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });

        final CountDownLatch serverMessagesLatch = new CountDownLatch(1);
        final ServerChannel metaConnectChannel = bayeuxServer.getChannel(Channel.META_CONNECT);
        metaConnectChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                // Check the queue when receiving the first /meta/connect.
                if (((ServerSessionImpl)from).getQueue().isEmpty() == allowHandshakeMessages) {
                    serverMessagesLatch.countDown();
                }
                metaConnectChannel.removeListener(this);
                return true;
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
                "    if (messages.length === 4) {" +
                "        clientMessagesLatch.countDown();" +
                "    }" +
                "};");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', listener);" +
                "cometd.addListener('" + channelName + "', listener);" +
                "cometd.addListener('/meta/connect', listener);");
        evaluateScript("" +
                "cometd.handshake();");

        Assert.assertTrue(serverMessagesLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientMessagesLatch.await(5000));

        evaluateScript("window.assert(messages[0].channel === '/meta/handshake', 'not handshake' + JSON.stringify(messages[0]));");
        evaluateScript("window.assert(messages[1].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[1]));");
        evaluateScript("window.assert(messages[2].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[2]));");
        evaluateScript("window.assert(messages[3].channel === '/meta/connect', 'not connect: ' + JSON.stringify(messages[3]));");
    }

    @Test
    public void testMessagesSentInHandshakeResponseWithAckExtensionWithDeQueueListener() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, String.valueOf(true));
        initCometDServer(options);
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

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
            }
        });
        provideMessageAcknowledgeExtension();

        final String channelName = "/test";
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                // Send messages during the handshake processing.
                session.deliver(null, channelName, "data1", Promise.noop());
                session.deliver(null, channelName, "data2", Promise.noop());
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
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

        Assert.assertTrue(clientMessagesLatch.await(5000));

        evaluateScript("window.assert(messages[0].channel === '/meta/handshake', 'not handshake' + JSON.stringify(messages[0]));");
        evaluateScript("window.assert(messages[1].channel === '" + channelName + "', 'not message' + JSON.stringify(messages[1]));");
        evaluateScript("window.assert(messages[2].channel === '/meta/connect', 'not connect: ' + JSON.stringify(messages[2]));");
    }
}
