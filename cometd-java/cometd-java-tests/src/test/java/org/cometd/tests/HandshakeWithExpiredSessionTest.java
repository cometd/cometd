/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HandshakeWithExpiredSessionTest extends AbstractClientServerTest {
    public HandshakeWithExpiredSessionTest(Transport transport) {
        super(transport);
    }

    @Before
    public void setUp() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, "2000");
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, "500");
        startServer(options);
    }

    @Test
    public void testHandshakeWithExpiredSession() throws Exception {
        final AtomicBoolean connect = new AtomicBoolean(true);
        TestBayeuxClient client = new TestBayeuxClient(connect);

        final CountDownLatch handshakeLatch1 = new CountDownLatch(1);
        final AtomicReference<ServerSession> sessionRef1 = new AtomicReference<>();
        final ClientSessionChannel metaHandshake = client.getChannel(Channel.META_HANDSHAKE);
        metaHandshake.addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                sessionRef1.set(bayeux.getSession(message.getClientId()));
                handshakeLatch1.countDown();
                metaHandshake.removeListener(this);
            }
        });

        final ClientSessionChannel metaConnect = client.getChannel(Channel.META_CONNECT);
        metaConnect.addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                // Disable connects after receiving the first.
                connect.compareAndSet(true, false);
                metaConnect.removeListener(this);
            }
        });

        client.handshake();

        Assert.assertTrue(handshakeLatch1.await(5, TimeUnit.SECONDS));

        ServerSession session1 = sessionRef1.get();
        final CountDownLatch removeLatch = new CountDownLatch(1);
        session1.addListener((ServerSession.RemoveListener)(session, timeout) -> removeLatch.countDown());

        final CountDownLatch handshakeLatch2 = new CountDownLatch(1);
        final AtomicReference<ServerSession> sessionRef2 = new AtomicReference<>();
        metaHandshake.addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            sessionRef2.set(bayeux.getSession(message.getClientId()));
            handshakeLatch2.countDown();
        });

        // Wait for the session to expire on the server.
        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));

        // Re-enable /meta/connect messages.
        connect.set(true);
        client.sendConnect();

        // Wait for the second handshake.
        Assert.assertTrue(handshakeLatch2.await(5, TimeUnit.SECONDS));

        // Sessions must be different.
        ServerSession session2 = sessionRef2.get();
        Assert.assertNotEquals(session1.getId(), session2.getId());

        disconnectBayeuxClient(client);
    }

    private class TestBayeuxClient extends BayeuxClient {
        private final AtomicBoolean connect;

        public TestBayeuxClient(AtomicBoolean connect) {
            super(cometdURL, newClientTransport(null));
            this.connect = connect;
        }

        @Override
        public void sendConnect() {
            if (connect.get())
                super.sendConnect();
        }
    }
}
