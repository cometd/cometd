package org.cometd.oort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private List<Seti> setis = new ArrayList<Seti>();

    protected Seti startSeti(Oort oort) throws Exception
    {
        Seti seti = new Seti(oort);
        seti.start();
        seti.getLogger().setDebugEnabled(true);
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

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1);
        BayeuxClient client2 = startClient(oort2);

        LatchListener publishLatch = new LatchListener();
        String loginChannelName = "/service/login";

        Map<String, Object> login1 = new HashMap<String, Object>();
        login1.put("user", "user1");
        ClientSessionChannel loginChannel1 = client1.getChannel(loginChannelName);
        loginChannel1.addListener(publishLatch);
        loginChannel1.publish(login1);
        Assert.assertTrue(publishLatch.await(1, TimeUnit.SECONDS));

        publishLatch.reset(1);
        Map<String, Object> login2 = new HashMap<String, Object>();
        login2.put("user", "user2");
        ClientSessionChannel loginChannel2 = client2.getChannel(loginChannelName);
        loginChannel2.addListener(publishLatch);
        loginChannel2.publish(login2);
        Assert.assertTrue(publishLatch.await(1, TimeUnit.SECONDS));

        // Wait for the associations to be broadcasted
        Thread.sleep(1000);

        String channel = "/service/forward";
        final CountDownLatch latch = new CountDownLatch(1);
        client2.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                latch.countDown();
            }
        });
        Map<String, Object> data1 = new HashMap<String, Object>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testDisassociate() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1);
        BayeuxClient client2 = startClient(oort2);

        Map<String, Object> login1 = new HashMap<String, Object>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);
        Map<String, Object> login2 = new HashMap<String, Object>();
        login2.put("user", "user2");
        client2.getChannel("/service/login").publish(login2);

        // Wait for the associations to be broadcasted
        Thread.sleep(1000);

        // Disassociate
        Map<String, Object> logout2 = new HashMap<String, Object>();
        logout2.put("user", "user2");
        client2.getChannel("/service/logout").publish(logout2);

        // Wait for the disassociation to be broadcasted
        Thread.sleep(1000);

        String channel = "/service/forward";
        final CountDownLatch latch = new CountDownLatch(1);
        client2.getChannel(channel).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                latch.countDown();
            }
        });
        Map<String, Object> data1 = new HashMap<String, Object>();
        data1.put("peer", "user2");
        client1.getChannel(channel).publish(data1);

        // User2 has been disassociated, must not receive the message
        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAutomaticDisassociation() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Seti seti1 = startSeti(oort1);
        Seti seti2 = startSeti(oort2);

        new SetiService(seti1);
        new SetiService(seti2);

        BayeuxClient client1 = startClient(oort1);
        Map<String, Object> login1 = new HashMap<String, Object>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);

        final AtomicReference<String> session2 = new AtomicReference<String>();
        BayeuxClient client2 = new BayeuxClient(oort2.getURL(), new LongPollingTransport(null, oort2.getHttpClient()))
        {
            @Override
            protected void processConnect(Message.Mutable connect)
            {
                // Send the login message, so Seti can associate this user
                Map<String, Object> login2 = new HashMap<String, Object>();
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
        Assert.assertTrue(client2.waitFor(1000, BayeuxClient.State.DISCONNECTED));

        // Wait for the server to expire client2 and for Seti to disassociate it
        final CountDownLatch latch = new CountDownLatch(1);
        oort2.getBayeuxServer().getSession(session2.get()).addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                latch.countDown();
            }
        });
        long maxTimeout = ((ServerTransport)oort2.getBayeuxServer().getTransport("long-polling")).getMaxInterval();
        Assert.assertTrue(latch.await(maxTimeout + 5000, TimeUnit.MILLISECONDS));

        // Sleep a little bit more, to be sure that all RemoveListeners have been processed
        Thread.sleep(1000);

        Assert.assertFalse(seti2.isAssociated("user2"));
    }

    private class SetiService extends AbstractService
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
            seti.disassociate(user);
        }

        public void forward(ServerSession session, ServerMessage message)
        {
            Map<String,Object> data = message.getDataAsMap();
            String peer = (String)data.get("peer");
            seti.sendMessage(peer, message.getChannel(), data);
        }
    }
}
