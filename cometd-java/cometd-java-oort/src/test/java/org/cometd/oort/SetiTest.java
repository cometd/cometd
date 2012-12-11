/*
 * Copyright (c) 2010 the original author or authors.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.AbstractService;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class SetiTest extends OortTest
{
    private List<Seti> setis = new ArrayList<>();

    protected Seti startSeti(Oort oort) throws Exception
    {
        Seti seti = new Seti(oort);
        seti.start();
        seti.setDebugEnabled(Boolean.getBoolean("debugTests"));
        setis.add(seti);
        return seti;
    }

    @After
    public void stopSetis() throws Exception
    {
        for (int i = setis.size() - 1; i >= 0; --i)
            stopSeti(setis.get(i));
    }

    protected void stopSeti(Seti seti) throws Exception
    {
        seti.stop();
    }

    @Test
    public void testAssociateAndSendMessage() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(2);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        BayeuxClient client2 = startClient(oort2, null);

        LatchListener publishLatch = new LatchListener();
        String loginChannelName = "/service/login";

        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        ClientSessionChannel loginChannel1 = client1.getChannel(loginChannelName);
        loginChannel1.addListener(publishLatch);
        loginChannel1.publish(login1);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client2.getChannel(loginChannelName);
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        String channel = "/service/forward";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        client2.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.countDown();
            }
        });
        Map<String, Object> data1 = new HashMap<>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDisassociate() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(2);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        BayeuxClient client2 = startClient(oort2, null);

        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        client2.getChannel("/service/login").publish(login2);

        Assert.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch absenceLatch = new CountDownLatch(1);
        UserAbsentListener absenceListener = new UserAbsentListener(absenceLatch);
        seti1.addPresenceListener(absenceListener);

        // Disassociate
        Map<String, Object> logout2 = new HashMap<>();
        logout2.put("user", "user2");
        client2.getChannel("/service/logout").publish(logout2);

        Assert.assertTrue(absenceLatch.await(5, TimeUnit.SECONDS));

        String channel = "/service/forward";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        client2.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.countDown();
            }
        });
        Map<String, Object> data1 = new HashMap<>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        // User2 has been disassociated, must not receive the message
        Assert.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAutomaticDisassociation() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        CountDownLatch presenceLatch = new CountDownLatch(2);
        UserPresentListener presenceListener = new UserPresentListener(presenceLatch);
        seti1.addPresenceListener(presenceListener);
        seti2.addPresenceListener(presenceListener);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Map<String, Object> login1 = new HashMap<>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);

        final AtomicReference<String> session2 = new AtomicReference<>();
        BayeuxClient client2 = new BayeuxClient(oort2.getURL(), new LongPollingTransport(null, oort2.getHttpClient()))
        {
            @Override
            protected void processConnect(Message.Mutable connect)
            {
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
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        Assert.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch absenceLatch = new CountDownLatch(1);
        seti1.addPresenceListener(new UserAbsentListener(absenceLatch));

        // Wait for the server to expire client2 and for Seti to disassociate it
        final CountDownLatch removedLatch = new CountDownLatch(1);
        oort2.getBayeuxServer().getSession(session2.get()).addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                removedLatch.countDown();
            }
        });
        long maxTimeout = ((ServerTransport)oort2.getBayeuxServer().getTransport("websocket")).getMaxInterval();
        Assert.assertTrue(removedLatch.await(maxTimeout + 5000, TimeUnit.MILLISECONDS));

        Assert.assertTrue(absenceLatch.await(5, TimeUnit.SECONDS));

        Assert.assertFalse(seti2.isAssociated("user2"));
    }

    @Test
    public void testAssociationWithMultipleSessions() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);
        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);

        CountDownLatch latch = new CountDownLatch(6);
        CometJoinedListener listener1 = new CometJoinedListener(latch);
        oort1.addCometListener(listener1);
        oort2.addCometListener(listener1);
        oort3.addCometListener(listener1);

        OortComet oortCometAB = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oort1.observeComet(oort3.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oort2.findComet(oort3.getURL());
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oort3.findComet(oort1.getURL());
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oort3.findComet(oort2.getURL());
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));

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
        BayeuxClient client1B = startClient(oort1, null);
        BayeuxClient client1C = startClient(oort2, null);
        BayeuxClient client3 = startClient(oort3, null);

        LatchListener publishLatch = new LatchListener();

        Map<String, Object> login1A = new HashMap<>();
        login1A.put("user", "user1");
        ClientSessionChannel loginChannel1A = client1A.getChannel("/service/login");
        loginChannel1A.addListener(publishLatch);
        loginChannel1A.publish(login1A);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Login the same user to the same server with a different client
        publishLatch.reset(1);
        Map<String, Object> login1B = new HashMap<>();
        login1B.put("user", "user1");
        ClientSessionChannel loginChannel1B = client1B.getChannel("/service/login");
        loginChannel1B.addListener(publishLatch);
        loginChannel1B.publish(login1B);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Login the same user to another server with a different client
        publishLatch.reset(1);
        Map<String, Object> login1C = new HashMap<>();
        login1C.put("user", "user1");
        ClientSessionChannel loginChannel1C = client1C.getChannel("/service/login");
        loginChannel1C.addListener(publishLatch);
        loginChannel1C.publish(login1C);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client3.getChannel("/service/login");
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(presenceLatch.await(5, TimeUnit.SECONDS));

        // Send a message from client3: client1A, client1B and client1C must receive it
        String channel = "/service/forward";
        final LatchListener messageLatch = new LatchListener(3);
        final AtomicInteger counter = new AtomicInteger();
        client1A.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                counter.incrementAndGet();
                messageLatch.countDown();
            }
        });
        client1B.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                counter.incrementAndGet();
                messageLatch.countDown();
            }
        });
        client1C.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                counter.incrementAndGet();
                messageLatch.countDown();
            }
        });
        Map<String, Object> data = new HashMap<>();
        data.put("peer", "user1");
        client3.getChannel(channel).publish(data);

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        // Wait a bit more to collect other messages that may be delivered wrongly
        Thread.sleep(1000);

        // Be sure exactly 3 have been delivered
        Assert.assertEquals(3, counter.get());

        // Disassociate client1A
        publishLatch.reset(1);
        Map<String, Object> logout = new HashMap<>();
        logout.put("user", "user1");
        ClientSessionChannel logoutChannel1A = client1A.getChannel("/service/logout");
        logoutChannel1A.addListener(publishLatch);
        logoutChannel1A.publish(logout);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        // Send again the message from client3, now only client1B and client1C must get it
        counter.set(0);
        messageLatch.reset(2);
        client3.getChannel(channel).publish(data);

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        // Wait a bit more to collect other messages that may be delivered wrongly
        Thread.sleep(1000);

        // Be sure exactly 2 have been delivered
        Assert.assertEquals(2, counter.get());
    }

    @Test
    public void testIsPresent() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1, null);
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch presenceOnLatch = new CountDownLatch(1);
        final CountDownLatch presenceOffLatch = new CountDownLatch(1);
        Seti.PresenceListener listener = new Seti.PresenceListener()
        {
            public void presenceAdded(Event event)
            {
                presenceOnLatch.countDown();
            }

            public void presenceRemoved(Event event)
            {
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
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(presenceOnLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(seti1.isAssociated(userId));
        Assert.assertTrue(seti1.isPresent(userId));
        Assert.assertTrue(seti2.isPresent(userId));

        publishLatch.reset(1);
        Map<String, Object> logout1 = new HashMap<>();
        logout1.put("user", userId);
        ClientSessionChannel logoutChannel1 = client1.getChannel("/service/logout");
        logoutChannel1.addListener(publishLatch);
        logoutChannel1.publish(logout1);
        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(presenceOffLatch.await(5, TimeUnit.SECONDS));

        Assert.assertFalse(seti1.isAssociated(userId));
        Assert.assertFalse(seti1.isPresent(userId));
        Assert.assertFalse(seti2.isPresent(userId));

        seti2.removePresenceListener(listener);
    }

    private static class SetiService extends AbstractService
    {
        private final Seti seti;

        private SetiService(Seti seti)
        {
            super(seti.getOort().getBayeuxServer(), seti.getId());
            this.seti = seti;
            addService("/service/login", "login");
            addService("/service/logout", "logout");
            addService("/service/forward", "forward");
        }

        public void login(ServerSession session, ServerMessage message)
        {
            Map<String,Object> data = message.getDataAsMap();
            String user = (String)data.get("user");
            seti.associate(user, session);
        }

        public void logout(ServerSession session, ServerMessage message)
        {
            Map<String,Object> data = message.getDataAsMap();
            String user = (String)data.get("user");
            seti.disassociate(user, session);
        }

        public void forward(ServerSession session, ServerMessage message)
        {
            Map<String,Object> data = message.getDataAsMap();
            String peer = (String)data.get("peer");
            seti.sendMessage(peer, message.getChannel(), data);
        }
    }

    private static class UserPresentListener implements Seti.PresenceListener
    {
        private final CountDownLatch latch;

        private UserPresentListener(CountDownLatch latch)
        {
            this.latch = latch;
        }

        public void presenceAdded(Event event)
        {
            latch.countDown();
        }

        public void presenceRemoved(Event event)
        {
        }
    }

    private static class UserAbsentListener implements Seti.PresenceListener
    {
        private final CountDownLatch latch;

        private UserAbsentListener(CountDownLatch latch)
        {
            this.latch = latch;
        }

        public void presenceAdded(Event event)
        {
        }

        public void presenceRemoved(Event event)
        {
            latch.countDown();
        }
    }
}
