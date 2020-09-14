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

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.cometd.server.websocket.javax.WebSocketEndPoint;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.eclipse.jetty.util.Callback;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test that in a slow network, an uninterrupted message flow due to a big
 * subscription won't cause the client to drop the connection
 */
public class CometDWebSocketSlowNetworkConnectTimeoutTest extends AbstractCometDWebSocketTest {
    private final long connectTimeout = 1000;

    /**
     * The socket will be throttled in order to send a message every 100ms
     */
    private static final int messageEvery = 100;

    /**
     * 100 messages * 100ms = flow of 10 seconds
     */
    private final int messageCount = 100;

    /**
     * Test that a continue message flow through a slow socket won't cause connect
     * abort
     * 
     * @throws Exception
     */
    @Test
    public void testConnectTimeoutIsCanceledOnSteadyMessageFlow() throws Exception {
        bayeuxServer.addTransport(new SlowWebSocketTransport(bayeuxServer));

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        evaluateScript("var connectErrLatch = new Latch(1);");
        Latch connectErrLatch = javaScript.get("connectErrLatch");

        evaluateScript("cometd.configure({" + "url: '" + cometdURL + "', " + "connectTimeout: " + connectTimeout + ", "
                + "logLevel: '" + getLogLevel() + "', rearmNetworkDelayAfterMessage: true });");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) {"
                + "   if (cometd.getTransport().getType() === 'websocket' && message.successful) {"
                + "       handshakeLatch.countDown();" + "   }" + "});");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" + "   if (!message.successful) {"
                + "       connectErrLatch.countDown();" + "   }" + "});");

        evaluateScript("cometd.handshake()");
        Assert.assertTrue(handshakeLatch.await(2 * connectTimeout));

        // Wait to be sure we're not disconnected
        Assert.assertFalse(connectErrLatch.await(2 * connectTimeout));

        // Now receive messages
        evaluateScript("var messagesLatch = new Latch(" + messageCount + ");");
        Latch messagesLatch = javaScript.get("messagesLatch");
        // Now subscribe for a lot of messages, clogging the channel
        for (int i = 0; i < messageCount; i++) {
            evaluateScript("cometd.subscribe('/echo" + i + "', function(message) { messagesLatch.countDown(); });");
        }
        for (int i = 0; i < messageCount; i++) {
            evaluateScript("cometd.publish('/echo" + i + "', { dummy: 42 });");
        }

        // Wait for messageLatch to zero
        Assert.assertTrue(messagesLatch.await(30000));
        //Assert.assertEquals(messageCount * 2, extension.calls);

        //Object msgCount = evaluateScript("cometd._messages;");

        // Wait to be sure we're not disconnected in the middle
        Assert.assertFalse(connectErrLatch.await(2 * connectTimeout));

        // Test disconnection
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
    }

    public static class SlowWebSocketTransport extends WebSocketTransport {
        public SlowWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected Object newWebSocketEndPoint(BayeuxContext bayeuxContext) {
            return new SlowWebSocketEndPoint(this, bayeuxContext);
        }
    }

    public static class SlowWebSocketEndPoint extends WebSocketEndPoint {
        private final Logger _logger = LoggerFactory.getLogger(getClass());
        private final TimeoutScheduledService scheduler = new TimeoutScheduledService(messageEvery);
        private volatile Session _wsSession;

        public SlowWebSocketEndPoint(AbstractWebSocketTransport transport, BayeuxContext bayeuxContext) {
            super(transport, bayeuxContext);
        }

        @Override
        public void onOpen(final Session wsSession, final EndpointConfig config) {
            super.onOpen(wsSession, config);
            _wsSession = wsSession;
        }
    
        @Override
        protected Delegate newDelegate(final AbstractWebSocketTransport transport, final BayeuxContext bayeuxContext) {
            return new SlowDelegate(transport, bayeuxContext);
        }

        public class SlowDelegate extends WebSocketEndPoint.Delegate {
            public SlowDelegate(AbstractWebSocketTransport transport, BayeuxContext bayeuxContext) {
                super(transport, bayeuxContext);
            }
        
            @Override
            protected void send(final ServerSession session, final String data, final Callback callback) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Sending Delayed {}", data);
                }
    
                // Simulate slow channel
                scheduler.schedule(() -> {
                    _wsSession.getAsyncRemote().sendText(data, 
                        result -> {
                            final Throwable failure = result.getException();
                            if (failure == null) {
                                callback.succeeded();
                            } else {
                                callback.failed(failure);
                            }
                        }
                    );
                    return null;
                });
            }
    
            @Override
            public void close(final int code, final String reason) {
                try {
                    // Limits of the WebSocket APIs, otherwise an exception is thrown.
                    final String reason1 = reason.substring(0, Math.min(reason.length(), 30));
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Closing {}/{}", code, reason1);
                    }
                    scheduler.schedule(() -> {
                        _wsSession.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason1));
                        return null;
                    });
                } catch (final Throwable x) {
                    _logger.trace("Could not close WebSocket session " + _wsSession, x);
                }
            }
        }
    }
}
