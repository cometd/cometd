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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MaxNetworkDelayTest extends ClientServerTest {
    private final long timeout = 5000;

    @BeforeEach
    public void setUp() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("timeout", String.valueOf(timeout));
        start(params);
    }

    @Test
    public void testMaxNetworkDelayOnHandshake() throws Exception {
        long maxNetworkDelay = 2000;
        long sleep = maxNetworkDelay + maxNetworkDelay / 2;

        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    try {
                        Thread.sleep(sleep);
                        // If we are able to sleep the whole time, the test will fail
                    } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                        // This exception is expected, do nothing
                    }
                }
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        transport.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.addTransportListener(new TransportListener() {
            @Override
            public void onFailure(Throwable failure, List<? extends Message> messages) {
                if (failure instanceof TimeoutException) {
                    latch.countDown();
                }
            }
        });
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isSuccessful()) {
                latch.countDown();
            }
        });

        client.handshake();
        Assertions.assertTrue(latch.await(sleep, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testMaxNetworkDelayOnConnect() throws Exception {
        long maxNetworkDelay = 2000;
        long sleep = maxNetworkDelay + maxNetworkDelay / 2;

        bayeux.addExtension(new BayeuxServer.Extension() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    int c = connects.incrementAndGet();
                    if (c == 2) {
                        try {
                            Thread.sleep(sleep);
                            // If we are able to sleep the whole time, the test will fail
                        } catch (InterruptedException x) {
                            Thread.currentThread().interrupt();
                            // This exception is expected, do nothing
                        }
                    }
                }
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(3);
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        transport.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.addTransportListener(new TransportListener() {
            @Override
            public void onFailure(Throwable failure, List<? extends Message> messages) {
                if (failure instanceof TimeoutException) {
                    latch.countDown();
                }
            }
        });
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                int c = connects.incrementAndGet();
                if (c == 1 && message.isSuccessful()) {
                    latch.countDown();
                } else if (c == 2 && !message.isSuccessful()) {
                    latch.countDown();
                }
            }
        });

        client.handshake();
        long begin = System.nanoTime();
        Assertions.assertTrue(latch.await(timeout + sleep, TimeUnit.MILLISECONDS));
        long end = System.nanoTime();
        Assertions.assertTrue(end - begin > TimeUnit.MILLISECONDS.toNanos(timeout));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDynamicMaxNetworkDelay() throws Exception {
        long maxNetworkDelay1 = 2000;
        long maxNetworkDelay2 = 4000;
        long sleep = (maxNetworkDelay1 + maxNetworkDelay2) / 2;

        bayeux.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException x) {
                        // Ignore
                    }
                }
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(3);
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        transport.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay1);
        BayeuxClient client = new BayeuxClient(cometdURL, transport);
        client.addTransportListener(new TransportListener() {
            @Override
            public void onFailure(Throwable failure, List<? extends Message> messages) {
                if (failure instanceof TimeoutException) {
                    latch.countDown();
                }
            }
        });
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            private final AtomicInteger connects = new AtomicInteger();

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                int c = connects.incrementAndGet();
                if (c == 1 && !message.isSuccessful()) {
                    latch.countDown();
                    // Change dynamically the max network delay.
                    client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay2);
                } else if (c == 2 && message.isSuccessful()) {
                    latch.countDown();
                }
            }
        });

        client.handshake();
        Assertions.assertTrue(latch.await(3 * sleep, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }
}
