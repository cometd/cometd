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
package org.cometd.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackoffTest extends ClientServerTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackoffTest.class);

    @Test
    public void testBackoffAfterReconnect() throws Exception {
        startServer(null);

        final AtomicBoolean serverMetaConnect = new AtomicBoolean(true);
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                return serverMetaConnect.get();
            }
        });

        final BayeuxClient client = newBayeuxClient();

        final CountDownLatch latch = new CountDownLatch(9);
        client.addExtension(new ClientSession.Extension.Adapter() {
            private final AtomicInteger clientMetaConnects = new AtomicInteger();

            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    LOGGER.info("> {}", message);
                    int metaConnects = clientMetaConnects.incrementAndGet();
                    if (metaConnects == 1) {
                        latch.countDown();
                    } else if (metaConnects == 2) {
                        latch.countDown();
                    } else if (metaConnects == 3) {
                        latch.countDown();
                    } else if (metaConnects == 4) {
                        long backoff = client.getBackoff();
                        if (backoff > 0) {
                            latch.countDown();
                        }
                    } else if (metaConnects == 5) {
                        long backoff = client.getBackoff();
                        if (backoff == 0) {
                            latch.countDown();
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean rcvMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    LOGGER.info("< {}", message);
                    int metaConnects = clientMetaConnects.get();
                    if (metaConnects == 1) {
                        serverMetaConnect.set(false);
                        latch.countDown();
                    } else if (metaConnects == 2) {
                        if (!message.isSuccessful()) {
                            latch.countDown();
                        }
                    } else if (metaConnects == 3) {
                        if (!message.isSuccessful()) {
                            serverMetaConnect.set(true);
                            latch.countDown();
                        }
                    } else if (metaConnects == 4) {
                        if (message.isSuccessful()) {
                            latch.countDown();
                        }
                    }
                }
                return true;
            }
        });

        client.handshake();

        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
