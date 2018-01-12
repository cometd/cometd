/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class BayeuxClientCallbacksTest extends ClientServerTest {
    @Before
    public void init() throws Exception {
        startServer(null);
    }

    @Test
    public void testHandshakeCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();

        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeCallbackIsInvokedOnReHandshake() throws Exception {
        final AtomicBoolean canHandshake = new AtomicBoolean();
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                if (canHandshake.compareAndSet(false, true)) {
                    message.getAssociated().getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                    return false;
                }
                return true;
            }
        });


        BayeuxClient client = newBayeuxClient();

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    successLatch.countDown();
                } else {
                    failureLatch.countDown();
                }
            }
        });

        Assert.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDisconnectCallback() throws Exception {
        final BayeuxClient client = newBayeuxClient();

        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                client.disconnect(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        latch.countDown();
                    }
                });
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSubscriptionAllowedInvokesCallback() throws Exception {
        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/bar";
        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                client.getChannel(channelName).subscribe(new MessageListenerAdapter(), new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        latch.countDown();
                    }
                });
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscriptionDeniedInvokesCallback() throws Exception {
        final String channelName = "/bar";
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                return !channel.getId().equals(channelName);
            }
        });

        final BayeuxClient client = newBayeuxClient();

        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                client.getChannel(channelName).subscribe(new MessageListenerAdapter(), new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        latch.countDown();
                    }
                });
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testUnsubscriptionInvokesCallback() throws Exception {
        final BayeuxClient client = newBayeuxClient();

        final String channelName = "/bar";
        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                final MessageListenerAdapter listener = new MessageListenerAdapter();
                client.getChannel(channelName).subscribe(listener, new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        client.getChannel(channelName).unsubscribe(listener, new ClientSessionChannel.MessageListener() {
                            @Override
                            public void onMessage(ClientSessionChannel channel, Message message) {
                                latch.countDown();
                            }
                        });
                    }
                });
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishSuccessfulInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertTrue(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Publish again without callback, must not trigger the previous callback
        latch.set(new CountDownLatch(1));
        channel.publish(new HashMap());

        Assert.assertFalse(latch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishNotAllowedInvokesCallback() throws Exception {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                return false;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishFailedInvokesCallback() throws Exception {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
                return false;
            }
        });

        BayeuxClient client = newBayeuxClient();
        client.handshake();

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishWithServerDownInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        server.stop();

        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap(), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private class MessageListenerAdapter implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
        }
    }
}
