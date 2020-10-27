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
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.TransportListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerSessionExpirationTest extends ClientServerTest {
    private final Logger logger = LoggerFactory.getLogger(ServerSessionExpirationTest.class);

    @Test
    public void testExpirationCancelledByPublish() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        long timeout = 2000;
        serverOptions.put("timeout", String.valueOf(timeout));
        long maxInterval = 4000;
        serverOptions.put("maxInterval", String.valueOf(maxInterval));
        start(serverOptions);

        long backOffIncrement = 3000;
        AtomicBoolean networkDown = new AtomicBoolean();
        CountDownLatch failedConnect = new CountDownLatch(2);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient) {
            @Override
            public void send(TransportListener listener, List<Message.Mutable> messages) {
                logger.info("send {}", messages);
                if (messages.size() == 1 && Channel.META_CONNECT.equals(messages.get(0).getChannel())) {
                    if (networkDown.get()) {
                        logger.info("network down");
                        listener.onFailure(new Exception(), messages);
                        failedConnect.countDown();
                        return;
                    }
                }
                super.send(listener, messages);
            }
        });
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backOffIncrement);
        client.handshake();

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(client.getId());
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> {
            logger.info("removed");
            removeLatch.countDown();
        });

        networkDown.set(true);

        // The second /meta/connect returns, but network is down.
        // Third /meta/connect is attempted, but failed.
        // Wait for backOffIncrement.
        // Fourth /meta/connect is attempted, but failed.
        Assertions.assertTrue(failedConnect.await(2 * backOffIncrement, TimeUnit.MILLISECONDS));
        Assertions.assertFalse(client.isConnected());

        // Publish a message, should cancel the removal of the session.
        client.getChannel("/foo").publish("bar");
        networkDown.set(false);

        // We will now wait 2 backOffIncrements, so keep the session alive.
        Thread.sleep(backOffIncrement);
        client.getChannel("/foo").publish("bar");

        // After 2 backOffIncrements, we should connect again.
        Assertions.assertTrue(client.waitFor(3 * backOffIncrement, BayeuxClient.State.CONNECTED));

        // Make sure we never disconnected.
        Assertions.assertFalse(removeLatch.await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testExpirationNotCancelledByLackOfConnect() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        long timeout = 2000;
        serverOptions.put("timeout", String.valueOf(timeout));
        long maxInterval = 4000;
        serverOptions.put("maxInterval", String.valueOf(maxInterval));
        start(serverOptions);

        long backOffIncrement = 3000;
        AtomicBoolean networkDown = new AtomicBoolean();
        CountDownLatch failedConnect = new CountDownLatch(2);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient) {
            @Override
            public void send(TransportListener listener, List<Message.Mutable> messages) {
                logger.info("send {}", messages);
                if (messages.size() == 1 && Channel.META_CONNECT.equals(messages.get(0).getChannel())) {
                    if (networkDown.get()) {
                        logger.info("network down");
                        listener.onFailure(new Exception(), messages);
                        failedConnect.countDown();
                        return;
                    }
                }
                super.send(listener, messages);
            }
        });
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backOffIncrement);
        client.handshake();

        // Wait for the second /meta/connect.
        Thread.sleep(1000);

        CountDownLatch removeLatch = new CountDownLatch(1);
        ServerSession session = bayeux.getSession(client.getId());
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> {
            logger.info("removed");
            removeLatch.countDown();
        });

        networkDown.set(true);

        // The second /meta/connect returns, but network is down.
        // Third /meta/connect is attempted, but failed.
        // Wait for backOffIncrement.
        // Fourth /meta/connect is attempted, but failed.
        Assertions.assertTrue(failedConnect.await(2 * backOffIncrement, TimeUnit.MILLISECONDS));
        Assertions.assertFalse(client.isConnected());

        // Publish a message, should cancel the removal of the session.
        client.getChannel("/foo").publish("bar");

        // At this point, backOffIncrement has passed.
        // Verify that the session is still alive.
        Thread.sleep((maxInterval - backOffIncrement) * 2);
        Assertions.assertFalse(removeLatch.await(1, TimeUnit.SECONDS));

        // We never restore the network, so eventually
        // the server must expire the session after maxInterval.
        Assertions.assertTrue(removeLatch.await(2 * maxInterval, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testExpirationExtendedByNonConnectMessagesEventuallyExpires() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        long timeout = 2000;
        serverOptions.put("timeout", String.valueOf(timeout));
        long maxInterval = 4000;
        serverOptions.put("maxInterval", String.valueOf(maxInterval));
        start(serverOptions);

        BayeuxClient client = newBayeuxClient();
        client.addExtension(new ClientSession.Extension() {
            private int metaConnects;

            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    ++metaConnects;
                    // Don't send /meta/connect after the first.
                    return metaConnects <= 1;
                }
                return true;
            }
        });

        client.handshake();

        // The client sent /meta/handshake and the first /meta/connect
        // but won't sent more /meta/connect messages.
        long delay = maxInterval * 3 / 4;
        logger.info("waiting {} ms", delay);
        Thread.sleep(delay);

        // Send messages to extend the expiration time.
        client.getChannel("/foo").publish("data");
        logger.info("message sent, waiting {} ms", delay);
        Thread.sleep(delay);
        client.getChannel("/foo").publish("data");

        // Don't send more messages, the server must expire the session.
        long expire = maxInterval + maxInterval / 2;
        logger.info("message sent, waiting for expiration {} ms", expire);
        Thread.sleep(expire);

        Assertions.assertNull(bayeux.getSession(client.getId()));

        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }
}
