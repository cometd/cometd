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
package org.cometd.oort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class SetiTest extends OortTest {
    private final List<Seti> setis = new ArrayList<>();

    protected Seti startSeti(Oort oort) throws Exception {
        Seti seti = new Seti(oort);
        seti.start();
        setis.add(seti);
        return seti;
    }

    @AfterEach
    public void stopSetis() throws Exception {
        for (int i = setis.size() - 1; i >= 0; --i) {
            stopSeti(setis.get(i));
        }
    }

    protected void stopSeti(Seti seti) throws Exception {
        seti.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAssociateAndSendMessage(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(4);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        LatchListener publishLatch = new LatchListener();
        String loginChannelName = "/service/login";

        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        ClientSessionChannel loginChannel1 = client1.getChannel(loginChannelName);
        loginChannel1.addListener(publishLatch);
        loginChannel1.publish(login1);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client2.getChannel(loginChannelName);
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        String channel = "/service/forward";
        CountDownLatch messageLatch = new CountDownLatch(1);
        client2.getChannel(channel).addListener((ClientSessionChannel.MessageListener)(c, m) -> messageLatch.countDown());
        Map<String, Object> data1 = new HashMap<>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAssociateWithAllChannelsSubscription(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        // Subscribe to /**, which is treated specially in Oort and Seti.
        oort1.observeChannel("/**");
        oort2.observeChannel("/**");
        // Wait a while to be sure to be subscribed.
        Thread.sleep(1000);

        CountDownLatch presenceLatch = new CountDownLatch(4);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        LatchListener publishLatch = new LatchListener();
        String loginChannelName = "/service/login";

        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        ClientSessionChannel loginChannel1 = client1.getChannel(loginChannelName);
        loginChannel1.addListener(publishLatch);
        loginChannel1.publish(login1);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client2.getChannel(loginChannelName);
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisassociate(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(4);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        client2.getChannel("/service/login").publish(login2);

        Assertions.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch absenceLatch = new CountDownLatch(1);
        UserAbsentListener absenceListener = new UserAbsentListener(absenceLatch);
        seti1.addPresenceListener(absenceListener);

        // Disassociate
        Map<String, Object> logout2 = new HashMap<>();
        logout2.put("user", "user2");
        client2.getChannel("/service/logout").publish(logout2);

        Assertions.assertTrue(absenceLatch.await(5, TimeUnit.SECONDS));

        String channel = "/service/forward";
        CountDownLatch messageLatch = new CountDownLatch(1);
        client2.getChannel(channel).addListener((ClientSessionChannel.MessageListener)(c, m) -> messageLatch.countDown());
        Map<String, Object> data1 = new HashMap<>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        // User2 has been disassociated, must not receive the message
        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAutomaticDisassociation(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(4);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);

        AtomicReference<String> session2 = new AtomicReference<>();
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        BayeuxClient client2 = new BayeuxClient(oort2.getURL(), new JettyHttpClientTransport(null, httpClient)) {
            @Override
            protected void processConnect(Message.Mutable connect) {
                // Send the login message, so Seti can associate this user
                Map<String, Object> login2 = new HashMap<>();
                login2.put("user", "user2");
                getChannel("/service/login").publish(login2);

                // Modify the advice so that it does not reconnect,
                // simulating that the client is gone so the server expires it
                session2.set(getId());
                connect.setSuccessful(false);
                connect.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                super.processConnect(connect);
            }
        };
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        httpClient.stop();

        Assertions.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch absenceLatch = new CountDownLatch(1);
        seti1.addPresenceListener(new UserAbsentListener(absenceLatch));

        // Wait for the server to expire client2 and for Seti to disassociate it
        CountDownLatch removedLatch = new CountDownLatch(1);
        oort2.getBayeuxServer().getSession(session2.get()).addListener((ServerSession.RemovedListener)(s, m, t) -> removedLatch.countDown());
        long maxTimeout = ((ServerTransport)oort2.getBayeuxServer().getTransport("websocket")).getMaxInterval();
        Assertions.assertTrue(removedLatch.await(maxTimeout + 5000, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(absenceLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(seti2.isAssociated("user2"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAssociationWithMultipleSessions(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);
        Server server3 = startServer(serverTransport, 0);
        Oort oort3 = startOort(server3);

        CountDownLatch latch = new CountDownLatch(6);
        CometJoinedListener listener1 = new CometJoinedListener(latch);
        oort1.addCometListener(listener1);
        oort2.addCometListener(listener1);
        oort3.addCometListener(listener1);

        OortComet oortCometAB = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oort1.observeComet(oort3.getURL());
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oort2.findComet(oort3.getURL());
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oort3.findComet(oort1.getURL());
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oort3.findComet(oort2.getURL());
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);
        Seti seti3 = startSeti(oort3);

        CountDownLatch presenceLatch = new CountDownLatch(6);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);
        seti3.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);
        new SetiService(seti3);

        BayeuxClient client1A = startClient(oort1, null);
        Assertions.assertTrue(client1A.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client1B = startClient(oort1, null);
        Assertions.assertTrue(client1B.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client1C = startClient(oort2, null);
        Assertions.assertTrue(client1C.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client3 = startClient(oort3, null);
        Assertions.assertTrue(client3.waitFor(5000, BayeuxClient.State.CONNECTED));

        LatchListener publishLatch = new LatchListener();

        Map<String, Object> login1A = new HashMap<>();
        login1A.put("user", "user1");
        ClientSessionChannel loginChannel1A = client1A.getChannel("/service/login");
        loginChannel1A.addListener(publishLatch);
        loginChannel1A.publish(login1A);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Login the same user to the same server with a different client
        publishLatch.reset(1);
        Map<String, Object> login1B = new HashMap<>();
        login1B.put("user", "user1");
        ClientSessionChannel loginChannel1B = client1B.getChannel("/service/login");
        loginChannel1B.addListener(publishLatch);
        loginChannel1B.publish(login1B);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Login the same user to another server with a different client
        publishLatch.reset(1);
        Map<String, Object> login1C = new HashMap<>();
        login1C.put("user", "user1");
        ClientSessionChannel loginChannel1C = client1C.getChannel("/service/login");
        loginChannel1C.addListener(publishLatch);
        loginChannel1C.publish(login1C);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client3.getChannel("/service/login");
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        // Send a message from client3: client1A, client1B and client1C must receive it
        String channel = "/service/forward";
        LatchListener messageLatch = new LatchListener(3);
        AtomicInteger counter = new AtomicInteger();
        client1A.getChannel(channel).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            counter.incrementAndGet();
            messageLatch.countDown();
        });
        client1B.getChannel(channel).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            counter.incrementAndGet();
            messageLatch.countDown();
        });
        client1C.getChannel(channel).addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            counter.incrementAndGet();
            messageLatch.countDown();
        });
        Map<String, Object> data = new HashMap<>();
        data.put("peer", "user1");
        client3.getChannel(channel).publish(data);

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        // Wait a bit more to collect other messages that may be delivered wrongly
        Thread.sleep(1000);

        // Be sure exactly 3 have been delivered
        Assertions.assertEquals(3, counter.get());

        // Disassociate client1A
        publishLatch.reset(1);
        Map<String, Object> logout = new HashMap<>();
        logout.put("user", "user1");
        ClientSessionChannel logoutChannel1A = client1A.getChannel("/service/logout");
        logoutChannel1A.addListener(publishLatch);
        logoutChannel1A.publish(logout);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Send again the message from client3, now only client1B and client1C must get it
        counter.set(0);
        messageLatch.reset(2);
        client3.getChannel(channel).publish(data);

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        // Wait a bit more to collect other messages that may be delivered wrongly
        Thread.sleep(1000);

        // Be sure exactly 2 have been delivered
        Assertions.assertEquals(2, counter.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIsPresent(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch presenceOnLatch = new CountDownLatch(1);
        CountDownLatch presenceOffLatch = new CountDownLatch(1);
        Seti.PresenceListener listener = new Seti.PresenceListener() {
            @Override
            public void presenceAdded(Event event) {
                presenceOnLatch.countDown();
            }

            @Override
            public void presenceRemoved(Event event) {
                presenceOffLatch.countDown();
            }
        };
        seti2.addPresenceListener(listener);

        LatchListener publishLatch = new LatchListener();
        Map<String, Object> login1 = new HashMap<>();
        String userId = "user1";
        login1.put("user", userId);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.addListener(publishLatch);
        loginChannel1.publish(login1);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(presenceOnLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(seti1.isAssociated(userId));
        Assertions.assertTrue(seti1.isPresent(userId));
        Assertions.assertTrue(seti2.isPresent(userId));

        publishLatch.reset(1);
        Map<String, Object> logout1 = new HashMap<>();
        logout1.put("user", userId);
        ClientSessionChannel logoutChannel1 = client1.getChannel("/service/logout");
        logoutChannel1.addListener(publishLatch);
        logoutChannel1.publish(logout1);
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(presenceOffLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertFalse(seti1.isAssociated(userId));
        Assertions.assertFalse(seti1.isPresent(userId));
        Assertions.assertFalse(seti2.isPresent(userId));

        seti2.removePresenceListener(listener);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIsPresentWhenNodeJoins(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Seti seti1 = startSeti(oort1);
        new SetiService(seti1);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        Map<String, Object> login1 = new HashMap<>();
        String userId = "user1";
        login1.put("user", userId);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        CountDownLatch publishLatch = new CountDownLatch(1);
        loginChannel1.publish(login1, message -> publishLatch.countDown());
        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Now user1 is associated on node1, start node2

        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti2 = startSeti(oort2);

        // Wait for the Seti state to broadcast
        Thread.sleep(1000);

        Assertions.assertTrue(seti2.isPresent(userId));

        // Stop Seti1
        CountDownLatch presenceOffLatch = new CountDownLatch(1);
        seti2.addPresenceListener(new Seti.PresenceListener() {
            @Override
            public void presenceRemoved(Event event) {
                presenceOffLatch.countDown();
            }
        });
        stopSeti(seti1);
        Assertions.assertTrue(presenceOffLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPresenceFiresEventLocally(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch localPresenceOnLatch = new CountDownLatch(1);
        CountDownLatch remotePresenceOnLatch = new CountDownLatch(1);
        CountDownLatch localPresenceOffLatch = new CountDownLatch(1);
        CountDownLatch remotePresenceOffLatch = new CountDownLatch(1);
        Seti.PresenceListener listener = new Seti.PresenceListener() {
            @Override
            public void presenceAdded(Event event) {
                if (event.isLocal()) {
                    localPresenceOnLatch.countDown();
                } else {
                    remotePresenceOnLatch.countDown();
                }
            }

            @Override
            public void presenceRemoved(Event event) {
                if (event.isLocal()) {
                    localPresenceOffLatch.countDown();
                } else {
                    remotePresenceOffLatch.countDown();
                }
            }
        };
        seti2.addPresenceListener(listener);

        // Login user1
        CountDownLatch loginLatch1 = new CountDownLatch(1);
        Map<String, Object> login1 = new HashMap<>();
        String userId1 = "user1";
        login1.put("user", userId1);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.publish(login1, message -> loginLatch1.countDown());
        Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(remotePresenceOnLatch.await(5, TimeUnit.SECONDS));

        // Login user2
        CountDownLatch loginLatch2 = new CountDownLatch(1);
        Map<String, Object> login2 = new HashMap<>();
        String userId2 = "user2";
        login2.put("user", userId2);
        ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
        loginChannel2.publish(login2, message -> loginLatch2.countDown());
        Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(localPresenceOnLatch.await(5, TimeUnit.SECONDS));

        // Logout user2
        CountDownLatch logoutLatch2 = new CountDownLatch(1);
        Map<String, Object> logout2 = new HashMap<>();
        logout2.put("user", userId2);
        ClientSessionChannel logoutChannel2 = client2.getChannel("/service/logout");
        logoutChannel2.publish(logout2, message -> logoutLatch2.countDown());
        Assertions.assertTrue(logoutLatch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(localPresenceOffLatch.await(5, TimeUnit.SECONDS));

        // Logout user1
        CountDownLatch logoutLatch1 = new CountDownLatch(1);
        Map<String, Object> logout1 = new HashMap<>();
        logout1.put("user", userId1);
        ClientSessionChannel logoutChannel1 = client1.getChannel("/service/logout");
        logoutChannel1.publish(logout1, message -> logoutLatch1.countDown());
        Assertions.assertTrue(logoutLatch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(remotePresenceOffLatch.await(5, TimeUnit.SECONDS));

        seti2.removePresenceListener(listener);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testStopRemovesAssociationsAndPresences(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch presenceAddedLatch = new CountDownLatch(4);
        seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
        seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

        // Login user1
        CountDownLatch loginLatch1 = new CountDownLatch(1);
        Map<String, Object> login1 = new HashMap<>();
        String userId1 = "user1";
        login1.put("user", userId1);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.publish(login1, message -> loginLatch1.countDown());
        Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));

        // Login user2
        CountDownLatch loginLatch2 = new CountDownLatch(1);
        Map<String, Object> login2 = new HashMap<>();
        String userId2 = "user2";
        login2.put("user", userId2);
        ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
        loginChannel2.publish(login2, message -> loginLatch2.countDown());
        Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

        // Make sure all Setis see all users
        Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch presenceRemovedLatch = new CountDownLatch(1);
        seti2.addPresenceListener(new UserAbsentListener(presenceRemovedLatch));

        // Stop Seti1
        stopSeti(seti1);

        Assertions.assertTrue(presenceRemovedLatch.await(5, TimeUnit.SECONDS));

        // Make sure Seti1 is cleared
        Assertions.assertFalse(seti1.isAssociated(userId1));
        Assertions.assertFalse(seti1.isPresent(userId2));

        // Make sure user1 is gone from Seti2
        Assertions.assertTrue(seti2.isAssociated(userId2));
        Assertions.assertFalse(seti2.isPresent(userId1));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNetworkDisconnectAndReconnect(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch presenceAddedLatch = new CountDownLatch(4);
        seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
        seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

        // Login user1
        CountDownLatch loginLatch1 = new CountDownLatch(1);
        Map<String, Object> login1 = new HashMap<>();
        String userId1 = "user1";
        login1.put("user", userId1);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.publish(login1, message -> loginLatch1.countDown());
        Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));

        // Login user2
        CountDownLatch loginLatch2 = new CountDownLatch(1);
        Map<String, Object> login2 = new HashMap<>();
        String userId2 = "user2";
        login2.put("user", userId2);
        ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
        loginChannel2.publish(login2, message -> loginLatch2.countDown());
        Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

        // Make sure all Setis see all users
        Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch presenceRemovedLatch = new CountDownLatch(2);
        seti1.addPresenceListener(new UserAbsentListener(presenceRemovedLatch));
        seti2.addPresenceListener(new UserAbsentListener(presenceRemovedLatch));

        // Simulate network crash
        oortComet12.disconnect();
        oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED);
        // The other OortComet is automatically disconnected
        oortComet21.waitFor(5000, BayeuxClient.State.DISCONNECTED);

        Assertions.assertTrue(presenceRemovedLatch.await(5, TimeUnit.SECONDS));

        // Make sure user1 is gone from Seti2
        Assertions.assertTrue(seti1.isAssociated(userId1));
        Assertions.assertFalse(seti2.isPresent(userId1));

        // Make sure user2 is gone from Seti1
        Assertions.assertTrue(seti2.isAssociated(userId2));
        Assertions.assertFalse(seti2.isPresent(userId1));
        Assertions.assertEquals(1, seti2.getAssociationCount(userId2));

        // Simulate network is up again
        presenceAddedLatch = new CountDownLatch(2);
        seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
        seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

        latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(seti1.isAssociated(userId1));
        Assertions.assertTrue(seti1.isPresent(userId2));
        Assertions.assertTrue(seti2.isAssociated(userId2));
        Assertions.assertTrue(seti2.isPresent(userId1));

        Set<String> userIds = seti1.getUserIds();
        Assertions.assertEquals(2, userIds.size());
        Assertions.assertTrue(userIds.contains(userId1));
        Assertions.assertTrue(userIds.contains(userId2));
        Assertions.assertEquals(1, seti1.getAssociationCount(userId1));
        Assertions.assertEquals(0, seti1.getAssociationCount(userId2));
        Assertions.assertEquals(1, seti1.getPresenceCount(userId1));
        Assertions.assertEquals(1, seti1.getPresenceCount(userId2));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMultipleServerCrashes(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch oortLatch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(oortLatch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortLatch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch presenceAddedLatch = new CountDownLatch(2);
        seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
        seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

        // Login user1
        CountDownLatch loginLatch1 = new CountDownLatch(1);
        Map<String, Object> login1 = new HashMap<>();
        String userId1 = "user1";
        login1.put("user", userId1);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.publish(login1, message -> loginLatch1.countDown());
        Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

        int switches = 2;
        for (int i = 0; i < switches; ++i) {
            // Simulate network crash
            oortComet12.disconnect();
            oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED);
            // The other OortComet is automatically disconnected
            oortComet21.waitFor(5000, BayeuxClient.State.DISCONNECTED);

            // Stop node1
            int port1 = ((NetworkConnector)server1.getConnectors()[0]).getLocalPort();
            stopSeti(seti1);
            stopOort(oort1);
            stopServer(server1);

            // Disconnect user and login it to node2
            client1.disconnect();
            Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.DISCONNECTED));
            client1 = startClient(oort2, null);
            CountDownLatch loginLatch2 = new CountDownLatch(1);
            loginChannel1 = client1.getChannel("/service/login");
            loginChannel1.publish(login1, message -> loginLatch2.countDown());
            Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

            // Bring node1 back online
            server1 = startServer(serverTransport, port1);
            oort1 = startOort(server1);
            oortLatch = new CountDownLatch(1);
            oort2.addCometListener(new CometJoinedListener(oortLatch));
            oortComet12 = oort1.observeComet(oort2.getURL());
            Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
            Assertions.assertTrue(oortLatch.await(5, TimeUnit.SECONDS));
            oortComet21 = oort2.findComet(oort1.getURL());
            Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
            seti1 = startSeti(oort1);
            new SetiService(seti1);
            // Wait for cloud/seti notifications to happen
            Thread.sleep(1000);

            Assertions.assertFalse(seti1.isAssociated(userId1));
            Assertions.assertTrue(seti1.isPresent(userId1));
            Assertions.assertTrue(seti2.isAssociated(userId1));
            Assertions.assertTrue(seti2.isPresent(userId1));

            // Simulate network crash
            oortComet12.disconnect();
            oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED);
            // The other OortComet is automatically disconnected
            oortComet21.waitFor(5000, BayeuxClient.State.DISCONNECTED);

            // Stop node2
            int port2 = ((NetworkConnector)server2.getConnectors()[0]).getLocalPort();
            stopSeti(seti2);
            stopOort(oort2);
            stopServer(server2);

            // Disconnect user and login it to node1
            client1.disconnect();
            Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.DISCONNECTED));
            client1 = startClient(oort1, null);
            CountDownLatch loginLatch3 = new CountDownLatch(1);
            loginChannel1 = client1.getChannel("/service/login");
            loginChannel1.publish(login1, message -> loginLatch3.countDown());
            Assertions.assertTrue(loginLatch3.await(5, TimeUnit.SECONDS));

            // Bring node2 back online
            server2 = startServer(serverTransport, port2);
            oort2 = startOort(server2);
            oortLatch = new CountDownLatch(1);
            oort1.addCometListener(new CometJoinedListener(oortLatch));
            oortComet21 = oort2.observeComet(oort1.getURL());
            Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
            Assertions.assertTrue(oortLatch.await(5, TimeUnit.SECONDS));
            oortComet12 = oort1.findComet(oort2.getURL());
            Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
            seti2 = startSeti(oort2);
            new SetiService(seti2);
            // Wait for cloud/seti notifications to happen
            Thread.sleep(1000);

            Assertions.assertTrue(seti1.isAssociated(userId1));
            Assertions.assertTrue(seti1.isPresent(userId1));
            Assertions.assertFalse(seti2.isAssociated(userId1));
            Assertions.assertTrue(seti2.isPresent(userId1));
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessageToObservedChannelIsForwarded(String serverTransport) throws Exception {
        testForwardBehaviour(serverTransport, true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessageToNonObservedChannelIsNotForwarded(String serverTransport) throws Exception {
        testForwardBehaviour(serverTransport, false);
    }

    private void testForwardBehaviour(String serverTransport, boolean forward) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch presenceAddedLatch = new CountDownLatch(4);
        seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
        seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

        // Login user1
        CountDownLatch loginLatch1 = new CountDownLatch(1);
        Map<String, Object> login1 = new HashMap<>();
        String userId1 = "user1";
        login1.put("user", userId1);
        ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
        loginChannel1.publish(login1, message -> loginLatch1.countDown());
        Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));

        // Login user2
        CountDownLatch loginLatch2 = new CountDownLatch(1);
        Map<String, Object> login2 = new HashMap<>();
        String userId2 = "user2";
        login2.put("user", userId2);
        ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
        loginChannel2.publish(login2, message -> loginLatch2.countDown());
        Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

        // Make sure all Setis see all users
        Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

        // Setup test: register a service for the service channel
        // that broadcasts to another channel that is not observed
        String serviceChannel = "/service/foo";
        String broadcastChannel = "/foo";

        if (forward) {
            oort2.observeChannel(broadcastChannel);
            // Give some time for the subscribe to happen
            Thread.sleep(1000);
        }

        new BroadcastService(seti1, serviceChannel, broadcastChannel);

        // Subscribe user2
        LatchListener subscribeListener = new LatchListener(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        client2.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeListener);
        client2.getChannel(broadcastChannel).subscribe((channel, message) -> messageLatch.countDown());
        Assertions.assertTrue(subscribeListener.await(5, TimeUnit.SECONDS));

        client1.getChannel(serviceChannel).publish("data1");

        Assertions.assertEquals(forward, messageLatch.await(1, TimeUnit.SECONDS));
    }

    public static class BroadcastService extends AbstractService {
        private final String broadcastChannel;

        public BroadcastService(Seti seti, String channel, String broadcastChannel) {
            super(seti.getOort().getBayeuxServer(), seti.getId());
            this.broadcastChannel = broadcastChannel;
            addService(channel, "process");
        }

        public void process(ServerSession session, ServerMessage message) {
            getLocalSession().getChannel(broadcastChannel).publish("data2");
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrent(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        int threads = 64;
        int iterations = 32;
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);

        CountDownLatch presentLatch = new CountDownLatch(threads * iterations);
        seti2.addPresenceListener(new UserPresentListener(presentLatch));
        CountDownLatch absentLatch = new CountDownLatch(threads * iterations);
        seti2.addPresenceListener(new UserAbsentListener(absentLatch));

        for (int i = 0; i < threads; ++i) {
            int index = i;
            new Thread(() -> {
                try {
                    Map<String, ServerSession> sessions = new HashMap<>();

                    barrier.await();
                    for (int j = 0; j < iterations; ++j) {
                        String key = String.valueOf(index * iterations + j);
                        LocalSession localSession = oort1.getBayeuxServer().newLocalSession(key);
                        localSession.handshake();
                        ServerSession session = localSession.getServerSession();
                        sessions.put(key, session);
                        seti1.associate(key, session);
                    }

                    barrier.await();
                    for (Map.Entry<String, ServerSession> entry : sessions.entrySet()) {
                        seti1.disassociate(entry.getKey(), entry.getValue());
                    }
                } catch (Throwable x) {
                    x.printStackTrace();
                }
            }).start();
        }

        // Wait for all threads to be ready.
        barrier.await();

        // Wait for all threads to finish associations.
        Assertions.assertTrue(presentLatch.await(30, TimeUnit.SECONDS));

        // The 2 Setis should be in sync.
        Assertions.assertEquals(seti1.getUserIds(), seti2.getUserIds());

        // Start disassociations.
        barrier.await();

        // Wait for all threads to finish disassociations.
        Assertions.assertTrue(absentLatch.await(30, TimeUnit.SECONDS));

        // The 2 Setis should be empty.
        Assertions.assertEquals(0, seti1.getAssociatedUserIds().size());
        Assertions.assertEquals(0, seti1.getUserIds().size());
        Assertions.assertEquals(0, seti2.getUserIds().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisassociationRemovesListeners(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);

        Seti seti1 = startSeti(oort1);

        String user = "user";
        LocalSession localSession = oort1.getBayeuxServer().newLocalSession(user);
        localSession.handshake();
        ServerSession session = localSession.getServerSession();

        seti1.associate(user, session);
        seti1.disassociate(user, session);

        Assertions.assertEquals(0, ((ServerSessionImpl)session).getListeners().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisassociateAllSessions(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);

        Seti seti1 = startSeti(oort1);

        String user = "user";
        LocalSession localSession = oort1.getBayeuxServer().newLocalSession(user);
        localSession.handshake();
        ServerSession session = localSession.getServerSession();

        seti1.associate(user, session);
        Set<ServerSession> removedSessions = seti1.disassociate(user);

        Assertions.assertEquals(1, removedSessions.size());
        Assertions.assertEquals(session, removedSessions.iterator().next());
        Assertions.assertEquals(0, ((ServerSessionImpl)session).getListeners().size());

        // Don't throw when the userId is not known.
        Set<ServerSession> removedUnknownSessions = seti1.disassociate("unknown-user");
        Assertions.assertEquals(0, removedUnknownSessions.size());
    }

    private static class UserPresentListener implements Seti.PresenceListener {
        private final CountDownLatch latch;

        private UserPresentListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void presenceAdded(Event event) {
            latch.countDown();
        }
    }

    private static class UserAbsentListener implements Seti.PresenceListener {
        private final CountDownLatch latch;

        private UserAbsentListener(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void presenceRemoved(Event event) {
            latch.countDown();
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testShortHalfNetworkDisconnectionBetweenNodes(String serverTransport) throws Exception {
        String loggerName = "org.cometd";
        Configurator.setLevel(loggerName, Level.DEBUG);
        try {
            Map<String, String> options = new HashMap<>();
            long timeout = 2000;
            options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
            Server server1 = startServer(serverTransport, 0, options);
            BayeuxServerImpl bayeuxServer1 = (BayeuxServerImpl)server1.getAttribute(BayeuxServer.ATTRIBUTE);
            bayeuxServer1.setDetailedDump(true);
            Oort oort1 = startOort(server1);
            Server server2 = startServer(serverTransport, 0, options);
            String url2 = (String)server2.getAttribute(OortConfigServlet.OORT_URL_PARAM);
            BayeuxServerImpl bayeuxServer2 = (BayeuxServerImpl)server2.getAttribute(BayeuxServer.ATTRIBUTE);
            bayeuxServer2.setDetailedDump(true);
            bayeuxServer2.setOption(Server.class.getName(), server2);
            AtomicBoolean halfNetworkDown = new AtomicBoolean();
            Oort oort2 = new Oort(bayeuxServer2, url2) {
                @Override
                protected OortComet newOortComet(String cometURL, ClientTransport transport, ClientTransport[] otherTransports) {
                    return new OortComet(this, cometURL, getScheduler(), transport, otherTransports) {
                        {
                            addTransportListener(new TransportListener() {
                                @Override
                                public void onMessages(List<Message.Mutable> messages) {
                                    if (halfNetworkDown.get()) {
                                        logger.info("Network down for client receive {}", messages);
                                        messagesFailure(new Exception(), messages);
                                        messages.clear();
                                    }
                                }
                            });
                        }
                    };
                }
            };
            bayeuxServer2.addExtension(new HalfNetworkDownExtension(oort2, halfNetworkDown));
            oort2.start();
            oorts.add(oort2);

            CountDownLatch latch = new CountDownLatch(1);
            oort2.addCometListener(new CometJoinedListener(latch));
            OortComet oortComet12 = oort1.observeComet(oort2.getURL());
            Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            OortComet oortComet21 = oort2.findComet(oort1.getURL());
            Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

            Seti seti1 = startSeti(oort1);
            Seti seti2 = startSeti(oort2);

            new SetiService(seti1);
            new SetiService(seti2);

            BayeuxClient client1 = startClient(oort1, null);
            Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
            BayeuxClient client2 = startClient(oort2, null);
            Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

            // Wait for the /meta/connects to be held.
            Thread.sleep(1000);

            CountDownLatch presenceAddedLatch = new CountDownLatch(4);
            seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
            seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

            // Login user1
            CountDownLatch loginLatch1 = new CountDownLatch(1);
            Map<String, Object> login1 = new HashMap<>();
            String userId1 = "user1";
            login1.put("user", userId1);
            ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
            loginChannel1.publish(login1, message -> loginLatch1.countDown());
            Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));

            // Login user2
            CountDownLatch loginLatch2 = new CountDownLatch(1);
            Map<String, Object> login2 = new HashMap<>();
            String userId2 = "user2";
            login2.put("user", userId2);
            ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
            loginChannel2.publish(login2, message -> loginLatch2.countDown());
            Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

            // Make sure all Setis see all users.
            Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

            // Wait for the /meta/connects to be held after logins.
            Thread.sleep(1000);

            // Disconnect network between the nodes temporarily.
            halfNetworkDown.set(true);

            // Logout user1 and login user3, node2 won't see these changes.
            CountDownLatch logoutLatch1 = new CountDownLatch(1);
            Map<String, Object> logout1 = new HashMap<>();
            logout1.put("user", userId1);
            ClientSessionChannel logoutChannel1 = client1.getChannel("/service/logout");
            logoutChannel1.publish(logout1, message -> logoutLatch1.countDown());
            Assertions.assertTrue(logoutLatch1.await(5, TimeUnit.SECONDS));
            CountDownLatch loginLatch3 = new CountDownLatch(1);
            Map<String, Object> login3 = new HashMap<>();
            String userId3 = "user3";
            login3.put("user", userId3);
            loginChannel1.publish(login3, message -> loginLatch3.countDown());
            Assertions.assertTrue(loginLatch3.await(5, TimeUnit.SECONDS));

            // Network is down, so nodes are out of sync.
            Set<String> userIds1 = seti1.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds1, seti1.dump());
            Set<String> userIds2 = seti2.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user1", "user2")), userIds2, seti2.dump());

            CountDownLatch presenceLatch2 = new CountDownLatch(2);
            seti2.addPresenceListener(new Seti.PresenceListener() {
                @Override
                public void presenceAdded(Event event) {
                    if (!event.isLocal() && "user3".equals(event.getUserId())) {
                        presenceLatch2.countDown();
                    }
                }

                @Override
                public void presenceRemoved(Event event) {
                    if (!event.isLocal() && "user1".equals(event.getUserId())) {
                        presenceLatch2.countDown();
                    }
                }
            });

            // Reconnect network.
            halfNetworkDown.set(false);

            // Wait until the nodes sync again.
            Assertions.assertTrue(presenceLatch2.await(3 * timeout, TimeUnit.MILLISECONDS));

            userIds1 = seti1.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds1, seti1.dump());
            userIds2 = seti2.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds2, seti2.dump());
        } finally {
            Configurator.setLevel(loggerName, Level.INFO);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testLongHalfNetworkDisconnectionBetweenNodes(String serverTransport) throws Exception {
        String loggerName = "org.cometd";
        Configurator.setLevel(loggerName, Level.DEBUG);
        try {
            Map<String, String> options = new HashMap<>();
            long maxNetworkDelay = 1000;
            options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, String.valueOf(maxNetworkDelay));
            long timeout = 2000;
            options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
            long maxInterval = timeout + maxNetworkDelay + 1000;
            options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
            Server server1 = startServer(serverTransport, 0, options);
            String url1 = (String)server1.getAttribute(OortConfigServlet.OORT_URL_PARAM);
            BayeuxServerImpl bayeuxServer1 = (BayeuxServerImpl)server1.getAttribute(BayeuxServer.ATTRIBUTE);
            bayeuxServer1.setOption(Server.class.getName(), server1);
            bayeuxServer1.setDetailedDump(true);
            AtomicBoolean networkDown21 = new AtomicBoolean();
            Oort oort1 = new Oort(bayeuxServer1, url1) {
                @Override
                protected OortComet newOortComet(String cometURL, ClientTransport transport, ClientTransport[] otherTransports) {
                    return new OortComet(this, cometURL, getScheduler(), transport, otherTransports) {
                        {
                            addTransportListener(new TransportListener() {
                                @Override
                                public void onMessages(List<Message.Mutable> messages) {
                                    if (networkDown21.get()) {
                                        logger.info("Network down for client receive {}", messages);
                                        messagesFailure(new Exception(), messages);
                                        messages.clear();
                                    }
                                }
                            });
                        }
                    };
                }

                @Override
                protected void configureOortComet(OortComet oortComet) {
                    super.configureOortComet(oortComet);
                    oortComet.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, 250L);
                }
            };
            bayeuxServer1.addExtension(new HalfNetworkDownExtension(oort1, networkDown21));
            oort1.start();
            oorts.add(oort1);
            Server server2 = startServer(serverTransport, 0, options);
            String url2 = (String)server2.getAttribute(OortConfigServlet.OORT_URL_PARAM);
            BayeuxServerImpl bayeuxServer2 = (BayeuxServerImpl)server2.getAttribute(BayeuxServer.ATTRIBUTE);
            bayeuxServer2.setOption(Server.class.getName(), server2);
            bayeuxServer2.setDetailedDump(true);
            AtomicBoolean networkDown12 = new AtomicBoolean();
            Oort oort2 = new Oort(bayeuxServer2, url2) {
                @Override
                protected OortComet newOortComet(String cometURL, ClientTransport transport, ClientTransport[] otherTransports) {
                    return new OortComet(this, cometURL, getScheduler(), transport, otherTransports) {
                        {
                            addTransportListener(new TransportListener() {
                                @Override
                                public void onMessages(List<Message.Mutable> messages) {
                                    if (networkDown12.get()) {
                                        logger.info("Network down for client receive {}", messages);
                                        messagesFailure(new Exception(), messages);
                                        messages.clear();
                                    }
                                }
                            });
                        }
                    };
                }

                @Override
                protected void configureOortComet(OortComet oortComet) {
                    super.configureOortComet(oortComet);
                    oortComet.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, 250L);
                }
            };
            bayeuxServer2.addExtension(new HalfNetworkDownExtension(oort2, networkDown12));
            oort2.start();
            oorts.add(oort2);

            CountDownLatch latch = new CountDownLatch(1);
            oort2.addCometListener(new CometJoinedListener(latch));
            OortComet oortComet12 = oort1.observeComet(oort2.getURL());
            Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            OortComet oortComet21 = oort2.findComet(oort1.getURL());
            Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

            Seti seti1 = startSeti(oort1);
            Seti seti2 = startSeti(oort2);

            new SetiService(seti1);
            new SetiService(seti2);

            BayeuxClient client1 = startClient(oort1, null);
            Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
            BayeuxClient client2 = startClient(oort2, null);
            Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

            // Wait for the /meta/connects to be held.
            Thread.sleep(1000);

            CountDownLatch presenceAddedLatch = new CountDownLatch(4);
            seti1.addPresenceListener(new UserPresentListener(presenceAddedLatch));
            seti2.addPresenceListener(new UserPresentListener(presenceAddedLatch));

            // Login user1
            CountDownLatch loginLatch1 = new CountDownLatch(1);
            Map<String, Object> login1 = new HashMap<>();
            String userId1 = "user1";
            login1.put("user", userId1);
            ClientSessionChannel loginChannel1 = client1.getChannel("/service/login");
            loginChannel1.publish(login1, message -> loginLatch1.countDown());
            Assertions.assertTrue(loginLatch1.await(5, TimeUnit.SECONDS));

            // Login user2
            CountDownLatch loginLatch2 = new CountDownLatch(1);
            Map<String, Object> login2 = new HashMap<>();
            String userId2 = "user2";
            login2.put("user", userId2);
            ClientSessionChannel loginChannel2 = client2.getChannel("/service/login");
            loginChannel2.publish(login2, message -> loginLatch2.countDown());
            Assertions.assertTrue(loginLatch2.await(5, TimeUnit.SECONDS));

            // Make sure all Setis see all users.
            Assertions.assertTrue(presenceAddedLatch.await(5, TimeUnit.SECONDS));

            // Wait for the /meta/connects to be held after logins.
            Thread.sleep(1000);

            CountDownLatch leftLatch1 = new CountDownLatch(1);
            oort1.addCometListener(new CometLeftListener(leftLatch1));

            // Disconnect network between the nodes temporarily.
            networkDown12.set(true);
            networkDown21.set(true);

            // Logout user1 and login user3, node2 won't see this change.
            CountDownLatch logoutLatch1 = new CountDownLatch(1);
            Map<String, Object> logout1 = new HashMap<>();
            logout1.put("user", userId1);
            ClientSessionChannel logoutChannel1 = client1.getChannel("/service/logout");
            logoutChannel1.publish(logout1, message -> logoutLatch1.countDown());
            Assertions.assertTrue(logoutLatch1.await(5, TimeUnit.SECONDS));
            CountDownLatch loginLatch3 = new CountDownLatch(1);
            Map<String, Object> login3 = new HashMap<>();
            String userId3 = "user3";
            login3.put("user", userId3);
            loginChannel1.publish(login3, message -> loginLatch3.countDown());
            Assertions.assertTrue(loginLatch3.await(5, TimeUnit.SECONDS));

            // Network is down, so nodes are out of sync.
            Set<String> userIds1 = seti1.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds1, seti1.dump());
            Set<String> userIds2 = seti2.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user1", "user2")), userIds2, seti2.dump());

            // Restore half network.
            networkDown12.set(false);
            logger.info("NETWORK12 UP");

            // Wait for node2 left event on node1.
            // We need to explicitly remove the session, simulating that node1 does
            // not receive messages from node2 and therefore times out the session.
            bayeuxServer1.removeServerSession(bayeuxServer1.getSession(oortComet21.getId()), true);
            Assertions.assertTrue(leftLatch1.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));

            AtomicInteger presenceAddedCount1 = new AtomicInteger();
            AtomicInteger presenceRemovedCount1 = new AtomicInteger();
            CountDownLatch presenceLatch1 = new CountDownLatch(1);
            seti1.addPresenceListener(new Seti.PresenceListener() {
                @Override
                public void presenceAdded(Event event) {
                    logger.info("presence added on node1 {}", event);
                    presenceAddedCount1.incrementAndGet();
                    if (!event.isLocal() && "user2".equals(event.getUserId())) {
                        presenceLatch1.countDown();
                    }
                }

                @Override
                public void presenceRemoved(Event event) {
                    presenceRemovedCount1.incrementAndGet();
                }
            });

            AtomicInteger presenceAddedCount2 = new AtomicInteger();
            AtomicInteger presenceRemovedCount2 = new AtomicInteger();
            CountDownLatch presenceLatch2 = new CountDownLatch(2);
            seti2.addPresenceListener(new Seti.PresenceListener() {
                @Override
                public void presenceAdded(Event event) {
                    logger.info("presence added on node2 {}", event);
                    presenceAddedCount2.incrementAndGet();
                    if (!event.isLocal() && "user3".equals(event.getUserId())) {
                        presenceLatch2.countDown();
                    }
                }

                @Override
                public void presenceRemoved(Event event) {
                    logger.info("presence removed on node2 {}", event);
                    presenceRemovedCount2.incrementAndGet();
                    if (!event.isLocal() && "user1".equals(event.getUserId())) {
                        presenceLatch2.countDown();
                    }
                }
            });

            // Restore network.
            networkDown21.set(false);
            logger.info("NETWORK UP");

            // Wait until the nodes sync again.
            Assertions.assertTrue(presenceLatch1.await(15, TimeUnit.SECONDS));
            Assertions.assertTrue(presenceLatch2.await(15, TimeUnit.SECONDS));

            // Wait a bit more to be sure no other presence events are delivered.
            Thread.sleep(1000);
            Assertions.assertEquals(1, presenceAddedCount1.get());
            Assertions.assertEquals(0, presenceRemovedCount1.get());
            Assertions.assertEquals(1, presenceAddedCount2.get());
            Assertions.assertEquals(1, presenceRemovedCount2.get());

            userIds1 = seti1.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds1, seti1.dump());
            userIds2 = seti2.getUserIds();
            Assertions.assertEquals(new HashSet<>(Arrays.asList("user2", "user3")), userIds2, seti2.dump());
        } finally {
            Configurator.setLevel(loggerName, Level.INFO);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testProtectedSetiChannels(String serverTransport) throws Exception {
        Server server = startServer(serverTransport, 0);
        Oort oort = startOort(server);
        Seti seti = startSeti(oort);

        BayeuxClient client = startClient(oort, null);
        CountDownLatch setiSubscribeLatch = new CountDownLatch(1);
        client.getChannel("/seti/*").subscribe((channel, message) -> {}, message -> {
            // Must not be able to subscribe.
            if (!message.isSuccessful()) {
                setiSubscribeLatch.countDown();
            }
        });
        Assertions.assertTrue(setiSubscribeLatch.await(1, TimeUnit.SECONDS));

        CountDownLatch publishLatch1 = new CountDownLatch(1);
        client.getChannel("/seti/all").publish("data1", message -> {
            // Must not be able to publish.
            if (!message.isSuccessful()) {
                publishLatch1.countDown();
            }
        });
        Assertions.assertTrue(publishLatch1.await(1, TimeUnit.SECONDS));

        CountDownLatch publishLatch2 = new CountDownLatch(1);
        client.getChannel(seti.generateSetiChannel(seti.getId())).publish("data2", message -> {
            // Must not be able to publish.
            if (!message.isSuccessful()) {
                publishLatch2.countDown();
            }
        });
        Assertions.assertTrue(publishLatch2.await(1, TimeUnit.SECONDS));

        String broadcastChannel = "/broadcast";
        CountDownLatch allMessageLatch = new CountDownLatch(1);
        CountDownLatch allSubscribeLatch = new CountDownLatch(1);
        client.getChannel("/**").subscribe((channel, message) -> {
            String channelName = message.getChannel();
            if (channelName.startsWith("/seti") || channelName.equals(broadcastChannel)) {
                allMessageLatch.countDown();
            }
        }, message -> {
            if (message.isSuccessful()) {
                allSubscribeLatch.countDown();
            }
        });

        Assertions.assertTrue(allSubscribeLatch.await(5, TimeUnit.SECONDS));

        // Cause a Seti message to be broadcast.
        LocalSession session = oort.getBayeuxServer().newLocalSession("foo");
        session.handshake();
        seti.associate("foo", session.getServerSession());

        // Make sure it was not received.
        Assertions.assertFalse(allMessageLatch.await(1, TimeUnit.SECONDS));

        // Publish a non-Seti message, make sure it's received.
        client.getChannel(broadcastChannel).publish("hello");
        Assertions.assertTrue(allMessageLatch.await(5, TimeUnit.SECONDS));
    }

    public static class SetiService extends AbstractService {
        private final Seti seti;

        private SetiService(Seti seti) {
            super(seti.getOort().getBayeuxServer(), seti.getId());
            this.seti = seti;
            addService("/service/login", "login");
            addService("/service/logout", "logout");
            addService("/service/forward", "forward");
        }

        public void login(ServerSession session, ServerMessage message) {
            Map<String, Object> data = message.getDataAsMap();
            String user = (String)data.get("user");
            seti.associate(user, session);
        }

        public void logout(ServerSession session, ServerMessage message) {
            Map<String, Object> data = message.getDataAsMap();
            String user = (String)data.get("user");
            seti.disassociate(user, session);
        }

        public void forward(ServerSession session, ServerMessage message) {
            Map<String, Object> data = message.getDataAsMap();
            String peer = (String)data.get("peer");
            seti.sendMessage(peer, message.getChannel(), data);
        }
    }
}
