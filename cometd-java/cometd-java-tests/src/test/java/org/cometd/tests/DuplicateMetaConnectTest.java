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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.AbstractServerTransport;
import org.junit.Assert;
import org.junit.Test;

public class DuplicateMetaConnectTest extends AbstractClientServerTest {
    public DuplicateMetaConnectTest(Transport transport) {
        super(transport);
    }

    @Test
    public void testDuplicateMetaConnectWithoutFailingExistingMetaConnect() throws Exception {
        startServer(serverOptions());

        long backoff = 500;
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoff);
        TestBayeuxClient client = new TestBayeuxClient(cometdURL, newClientTransport(clientOptions));

        final BlockingQueue<Message> connects = new LinkedBlockingDeque<>();
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                connects.offer(message);
            }
        });

        client.handshake();

        // Wait for the /meta/connect to be held by the server.
        sleep(1000);

        Message connect1 = connects.poll(1, TimeUnit.SECONDS);
        Assert.assertNotNull(connect1);
        Assert.assertTrue(connect1.isSuccessful());

        // Send a duplicate /meta/connect without failing the existing one.
        client.sendConnect();

        // The previous /meta/connect should be dropped.
        Message connect2 = connects.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(connect2);

        // Wait for the backoff in case the /meta/connect failure triggers a retry.
        sleep(2 * backoff);

        // The new /meta/connect should be held.
        Message connect3 = connects.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(connect3);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDuplicateMetaConnectWithExistingMetaConnectFailedOnClient() throws Exception {
        long timeout = 2000;
        Map<String, String> serverOptions = serverOptions();
        serverOptions.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        startServer(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        final long maxNetworkDelay = 1000;
        clientOptions.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        long backoff = 1000;
        clientOptions.put(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoff);
        BayeuxClient client = new BayeuxClient(cometdURL, newClientTransport(clientOptions));

        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            private final AtomicInteger metaConnects = new AtomicInteger();
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                if (metaConnects.incrementAndGet() == 2) {
                    sleep(maxNetworkDelay + maxNetworkDelay / 2);
                }
                return true;
            }
        });

        final BlockingQueue<Message> connects = new LinkedBlockingDeque<>();
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                connects.offer(message);
            }
        });

        client.handshake();

        // Wait for the second /meta/connect to be held by the server.
        sleep(1000);

        Message connect1 = connects.poll(1, TimeUnit.SECONDS);
        Assert.assertNotNull(connect1);
        Assert.assertTrue(connect1.isSuccessful());

        // The second /meta/connect should be timed out by the client
        // and trigger the send of a third /meta/connect after one backoff.
        Message connect2 = connects.poll(timeout + maxNetworkDelay * 2, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connect2);
        Assert.assertFalse(connect2.isSuccessful());

        // The server returns the second /meta/connect,
        // but the client has closed the connection.

        // The client sends the third /meta/connect with advice: { timeout: 0 }.
        Message connect3 = connects.poll(2 * backoff, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(connect3);
        Assert.assertTrue(connect3.isSuccessful());

        // The fourth connect is held by the server.
        Message connect4 = connects.poll(timeout / 2, TimeUnit.MILLISECONDS);
        Assert.assertNull(connect4);

        disconnectBayeuxClient(client);
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            throw new RuntimeException(x);
        }
    }

    private static class TestBayeuxClient extends BayeuxClient {
        private TestBayeuxClient(String cometdURL, ClientTransport transport) {
            super(cometdURL, transport);
        }

        // For visibility.
        @Override
        protected boolean sendConnect() {
            return super.sendConnect();
        }
    }
}
