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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.server.DefaultSecurityPolicy;
import org.cometd.server.LocalSessionImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BayeuxClientCallbacksTest extends ClientServerTest {
    @BeforeEach
    public void init() throws Exception {
        start(null);
    }

    @Test
    public void testHandshakeCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();

        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> latch.countDown());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeCallbackIsInvokedOnReHandshake() throws Exception {
        AtomicBoolean canHandshake = new AtomicBoolean();
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

        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                successLatch.countDown();
            } else {
                failureLatch.countDown();
            }
        });

        Assertions.assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testDisconnectCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();

        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> client.disconnect(m -> latch.countDown()));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSubscriptionAllowedInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();

        String channelName = "/bar";
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> client.getChannel(channelName).subscribe(new MessageListenerAdapter(), m -> latch.countDown()));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscriptionDeniedInvokesCallback() throws Exception {
        String channelName = "/bar";
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                return !channel.getId().equals(channelName);
            }
        });

        BayeuxClient client = newBayeuxClient();

        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> client.getChannel(channelName).subscribe(new MessageListenerAdapter(), m -> latch.countDown()));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testUnsubscriptionInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();

        String channelName = "/bar";
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> {
            MessageListenerAdapter listener = new MessageListenerAdapter();
            client.getChannel(channelName).subscribe(listener, m -> client.getChannel(channelName).unsubscribe(listener, r -> latch.countDown()));
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishSuccessfulInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap<>(), message -> {
            Assertions.assertTrue(message.isSuccessful());
            latch.get().countDown();
        });

        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Publish again without callback, must not trigger the previous callback
        latch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());

        Assertions.assertFalse(latch.get().await(1, TimeUnit.SECONDS));

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
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap<>(), message -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });

        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

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

        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        // Publish will fail because we did not handshake.
        channel.publish(new HashMap<>(), message -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });

        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishWithServerDownInvokesCallback() throws Exception {
        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        server.stop();

        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel channel = client.getChannel("/test");
        channel.publish(new HashMap<>(), message -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.get().countDown();
        });

        Assertions.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishDeletedRemovesCallback() throws Exception {
        String channelName = "/delete";

        AtomicReference<String> messageIdRef = new AtomicReference<>();
        CountDownLatch unregisterLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        CountDownLatch publishLatch = new CountDownLatch(1);
        client.handshake(reply -> {
            if (reply.isSuccessful()) {
                client.getChannel(channelName).publish("data", message -> {
                    if (!message.isSuccessful()) {
                        publishLatch.countDown();
                    }
                });
            }
        });

        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testPublishReplyDeletedRemovesCallback() throws Exception {
        String channelName = "/delete";

        AtomicReference<String> messageIdRef = new AtomicReference<>();
        CountDownLatch unregisterLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean rcv(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        client.handshake(reply -> {
            if (reply.isSuccessful()) {
                client.getChannel(channelName).publish("data", ClientSession.MessageListener.NOOP);
            }
        });

        Assertions.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testSubscribeDeletedRemovesListener() throws Exception {
        String channelName = "/delete";

        AtomicReference<String> messageIdRef = new AtomicReference<>();
        CountDownLatch unregisterLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected ClientSessionChannel.MessageListener unregisterSubscriber(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterSubscriber(messageId);
            }
        };
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_SUBSCRIBE.equals(message.getChannel()) && channelName.equals(message.get(Message.SUBSCRIPTION_FIELD))) {
                    messageIdRef.set(message.getId());
                    return false;
                }
                return true;
            }
        });
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.handshake(reply -> {
            if (reply.isSuccessful()) {
                client.getChannel(channelName).subscribe(new MessageListenerAdapter(), message -> {
                    if (!message.isSuccessful()) {
                        subscribeLatch.countDown();
                    }
                });
            }
        });

        Assertions.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAuthorizerDenyingPublishRemovesCallback() throws Exception {
        String channelName = "/deny_publish";

        bayeux.createChannelIfAbsent(channelName, channel ->
                channel.addAuthorizer((operation, channel1, session, message) ->
                        operation == Authorizer.Operation.PUBLISH ? Authorizer.Result.deny("denied") : Authorizer.Result.grant()));

        AtomicReference<String> messageIdRef = new AtomicReference<>();
        CountDownLatch unregisterLatch = new CountDownLatch(1);
        LocalSession session = new LocalSessionImpl(bayeux, "test") {
            @Override
            protected MessageListener unregisterCallback(String messageId) {
                if (messageId.equals(messageIdRef.get())) {
                    unregisterLatch.countDown();
                }
                return super.unregisterCallback(messageId);
            }
        };
        session.addExtension(new ClientSession.Extension() {
            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                if (channelName.equals(message.getChannel())) {
                    messageIdRef.set(message.getId());
                }
                return true;
            }
        });
        session.handshake();

        CountDownLatch publishLatch = new CountDownLatch(1);
        session.getChannel(channelName).publish("data", message -> {
            if (!message.isSuccessful()) {
                publishLatch.countDown();
            }
        });

        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(unregisterLatch.await(5, TimeUnit.SECONDS));

        session.disconnect();
    }

    private static class MessageListenerAdapter implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
        }
    }
}
