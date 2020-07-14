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

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.DefaultSecurityPolicy;
import org.cometd.server.LocalSessionImpl;
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
        channel.publish(new HashMap<>(), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Assert.assertTrue(message.isSuccessful());
                latch.get().countDown();
            }
        });

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Publish again without callback, must not trigger the previous callback
        latch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());

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
        channel.publish(new HashMap<>(), new ClientSessionChannel.MessageListener() {
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
        // Handshake will fail.
        client.handshake();

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        // Publish will fail because we did not handshake.
        channel.publish(new HashMap<>(), new ClientSessionChannel.MessageListener() {
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

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap<>(), new ClientSessionChannel.MessageListener() {
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
    public void testPublishDeletedRemovesCallback() throws Exception {
        final String channelName = "/delete";

        final AtomicReference<String> messageIdRef = new AtomicReference<>();
        final CountDownLatch unregisterLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient)) {
            @Override
            protected ClientSessionChannel.MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    client.getChannel(channelName).publish("data", new MessageListenerAdapter());
                }
            }
        });

        Assert.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishReplyDeletedRemovesCallback() throws Exception {
        final String channelName = "/delete";

        final AtomicReference<String> messageIdRef = new AtomicReference<>();
        final CountDownLatch unregisterLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient)) {
            @Override
            protected ClientSessionChannel.MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean rcv(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    client.getChannel(channelName).publish("data", new MessageListenerAdapter());
                }
            }
        });

        Assert.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscribeDeletedRemovesListener() throws Exception {
        final String channelName = "/delete";

        final AtomicReference<String> messageIdRef = new AtomicReference<>();
        final CountDownLatch unregisterLatch = new CountDownLatch(1);
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient)) {
            @Override
            protected ClientSessionChannel.MessageListener unregisterSubscriber(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterSubscriber(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_SUBSCRIBE.equals(message.getChannel()) && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD))) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    client.getChannel(channelName).subscribe(new MessageListenerAdapter());
                }
            }
        });

        Assert.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAuthorizerDenyingPublishRemovesCallback() throws Exception {
        final String channelName = "/deny_publish";

        bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer() {
            @Override
            public void configureChannel(ConfigurableServerChannel channel) {
                channel.addAuthorizer(new Authorizer() {
                    @Override
                    public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
                        return operation == Operation.PUBLISH ? Result.deny("denied") : Result.grant();
                    }
                });
            }
        });

        final AtomicReference<String> messageIdRef = new AtomicReference<>();
        final CountDownLatch unregisterLatch = new CountDownLatch(1);
        LocalSession session = new LocalSessionImpl(bayeux, "test") {
            @Override
            protected ClientSessionChannel.MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        session.addExtension(new ClientSession.Extension.Adapter() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                }
                return true;
            }
        });
        session.handshake();

        session.getChannel(channelName).publish("data", new MessageListenerAdapter());

        Assert.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        session.disconnect();
    }

    private static class MessageListenerAdapter implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
        }
    }
}
