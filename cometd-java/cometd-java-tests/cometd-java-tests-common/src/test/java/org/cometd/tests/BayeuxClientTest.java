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

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class BayeuxClientTest extends AbstractClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testShortIdleTimeout(Transport transport) throws Exception {
        start(transport);
        int idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        List<Message> metaMessages = new CopyOnWriteArrayList<>();
        List<Message> fooMessages = new CopyOnWriteArrayList<>();

        BayeuxClient client = newBayeuxClient(transport);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> metaMessages.add(message));
        client.getChannel("/foo").addListener((ClientSessionChannel.MessageListener)(channel, message) -> fooMessages.add(message));
        client.handshake();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Promise.Completable<Boolean> promise1 = new Promise.Completable<>();
        bayeuxServer.getSession(client.getId()).deliver(null, "/foo", "hello 1", promise1);
        promise1.get();

        Thread.sleep(2 * idleTimeout);

        Promise.Completable<Boolean> promise2 = new Promise.Completable<>();
        bayeuxServer.getSession(client.getId()).deliver(null, "/foo", "hello 2", promise2);
        promise2.get();

        disconnectBayeuxClient(client);

        assertThat("Expected 2 messages: " + fooMessages, fooMessages.size(), is(2));
        assertThat(fooMessages.get(0).getData(), is("hello 1"));
        assertThat(fooMessages.get(1).getData(), is("hello 2"));

        for (Message metaMessage : metaMessages)
        {
            if (metaMessage instanceof HashMapMessage map)
            {
                assertThat("Expected no failure: " + map, map.containsKey("failure"), is(false));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIPv6Address(Transport transport) throws Exception {
        Assumptions.assumeTrue(ipv6Available());

        start(transport);

        cometdURL = cometdURL.replace("localhost", "[::1]");

        BayeuxClient client = newBayeuxClient(transport);
        client.handshake();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Allow long poll to establish
        Thread.sleep(1000);

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testBatchingAfterHandshake(Transport transport) throws Exception {
        start(transport);

        BayeuxClient client = newBayeuxClient(transport);
        AtomicBoolean connected = new AtomicBoolean();
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connected.set(message.isSuccessful()));
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connected.set(false));
        client.handshake();

        String channelName = "/foo/bar";
        BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();
        client.batch(() -> {
            // Subscribe and publish must be batched so that they are sent in order,
            // otherwise it's possible that the subscribe arrives to the server after the publish
            client.getChannel(channelName).subscribe((channel, message) -> {
                messages.add(channel.getId());
                messages.add(message.getData().toString());
            });
            client.getChannel(channelName).publish("hello");
        });

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(channelName, messages.poll(1, TimeUnit.SECONDS));
        Assertions.assertEquals("hello", messages.poll(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessageWithoutChannel(Transport transport) throws Exception {
        start(transport);

        BayeuxClient client = newBayeuxClient(transport);
        client.addExtension(new ClientSession.Extension() {
            @Override
            public void outgoing(ClientSession session, Message.Mutable message, Promise<Boolean> promise) {
                message.remove(Message.CHANNEL_FIELD);
                promise.succeed(true);
            }
        });

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void loadTest(Transport transport) throws Exception {
        start(transport);

        try {
            int rooms = 10;
            int publish = 100;
            int batch = 2;
            int pause = 10;
            Random random = new Random();
            BayeuxClient[] clients = new BayeuxClient[2 * rooms];

            AtomicInteger connections = new AtomicInteger();
            AtomicInteger received = new AtomicInteger();

            for (int i = 0; i < clients.length; i++) {
                AtomicBoolean connected = new AtomicBoolean();
                BayeuxClient client = newBayeuxClient(transport);
                String room = "/channel/" + (i % rooms);
                clients[i] = client;

                client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
                    if (connected.getAndSet(false)) {
                        connections.decrementAndGet();
                    }

                    if (message.isSuccessful()) {
                        client.getChannel(room).subscribe((c, m) -> received.incrementAndGet());
                    }
                });

                client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
                    if (!connected.getAndSet(message.isSuccessful())) {
                        connections.incrementAndGet();
                    }
                });

                clients[i].handshake();
                Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
            }

            Assertions.assertEquals(clients.length, connections.get());

            long start0 = System.nanoTime();
            for (int i = 0; i < publish; i++) {
                int sender = random.nextInt(clients.length);
                String channel = "/channel/" + random.nextInt(rooms);

                String data = "data from " + sender + " to " + channel;
                clients[sender].getChannel(channel).publish(data);

                if (i % batch == (batch - 1)) {
                    Thread.sleep(pause);
                }
            }

            int expected = clients.length * publish / rooms;

            long start = System.nanoTime();
            while (received.get() < expected && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10) {
                Thread.sleep(100);
            }
            logger.info("{} m/s", (received.get() * 1000 * 1000 * 1000L) / (System.nanoTime() - start0));

            Assertions.assertEquals(expected, received.get());

            for (BayeuxClient client : clients) {
                Assertions.assertTrue(client.disconnect(1000));
            }
        } catch (Throwable x) {
            switch (transport) {
                case OKHTTP_HTTP:
                case OKHTTP_WEBSOCKET:
                    // Ignore the failure as OkHttp is not that stable :(
                    break;
                default:
                    throw x;
            }
        }
    }
}
