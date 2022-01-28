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
package org.cometd.tests;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class HandshakeReconnectTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testReconnectUsingHandshake(Transport transport) throws Exception {
        long timeout = 1500;
        long maxInterval = 2000;
        Map<String, String> options = serverOptions(transport);
        options.put(AbstractServerTransport.HANDSHAKE_RECONNECT_OPTION, String.valueOf(true));
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        start(transport, options);

        BayeuxClient client = newBayeuxClient(transport);
        long backOffIncrement = 1000;
        client.setBackOffStrategy(new BayeuxClient.BackOffStrategy.Linear(backOffIncrement, -1));
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held.
        Thread.sleep(1000);

        // Stop the connector try to reconnect
        int port = connector.getLocalPort();
        connector.stop();

        // Add a /meta/handshake listener to be sure we reconnect using handshake.
        CountDownLatch handshakeReconnect = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Reconnecting using handshake, first failure.
            if (!message.isSuccessful()) {
                handshakeReconnect.countDown();
            }
        });

        // Wait for the session to be swept (timeout + maxInterval).
        CountDownLatch sessionRemoved = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                sessionRemoved.countDown();
            }
        });

        Assertions.assertTrue(sessionRemoved.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(handshakeReconnect.await(10 * backOffIncrement, TimeUnit.MILLISECONDS));

        // Restart the connector.
        connector.setPort(port);
        connector.start();

        Assertions.assertTrue(client.waitFor(20 * backOffIncrement, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }
}
