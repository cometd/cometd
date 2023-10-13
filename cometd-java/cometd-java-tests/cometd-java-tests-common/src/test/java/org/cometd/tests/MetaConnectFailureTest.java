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
package org.cometd.tests;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetaConnectFailureTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectSuspendedThenConnectionClosed(Transport transport) throws Exception {
        Map<String, String> options = serverOptions(transport);
        long timeout = 3000;
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        long maxInterval = 2000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        long sweepPeriod = 500;
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        start(transport, options);

        BayeuxClient client = newBayeuxClient(transport);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connect to be held by the server.
        Thread.sleep(500);

        // Verify that the /meta/connect reply is written.
        CountDownLatch metaConnectReplyLatch = new CountDownLatch(1);
        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession session, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    metaConnectReplyLatch.countDown();
                }
                return true;
            }
        });

        // Verify that the session is swept.
        CountDownLatch sessionRemovedLatch = new CountDownLatch(1);
        ServerSession session = bayeuxServer.getSession(client.getId());
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> sessionRemovedLatch.countDown());

        // Stop the connector so the /meta/connect response cannot be sent,
        // and more /meta/connect from client will not arrive to the server.
        // For HTTP transports, the /meta/connect is suspended and they will
        // not notice the connection close. The /meta/connect will be resumed,
        // the transport will try to write the /meta/connect reply and then
        // schedule the session expiration.
        // For WebSocket transports, they will notice that the connection has
        // been closed, either via onClose() or onError(), and they must behave
        // like the HTTP transports for consistency: write the /meta/connect
        // reply and then schedule the session expiration.
        connector.stop();

        Assertions.assertTrue(metaConnectReplyLatch.await(timeout, TimeUnit.MILLISECONDS));
        Assertions.assertTrue(sessionRemovedLatch.await(timeout + maxInterval + 2 * sweepPeriod, TimeUnit.MILLISECONDS));
    }
}
