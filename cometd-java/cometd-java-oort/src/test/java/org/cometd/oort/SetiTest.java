package org.cometd.oort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
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

        Map<String, Object> login1 = new HashMap<String, Object>();
        login1.put("user", "user1");
        client1.getChannel("/service/login").publish(login1);
        Map<String, Object> login2 = new HashMap<String, Object>();
        login2.put("user", "user2");
        client2.getChannel("/service/login").publish(login2);

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

        // Disassociate
        Map<String, Object> logout2 = new HashMap<String, Object>();
        logout2.put("user", "user2");
        client2.getChannel("/service/logout").publish(logout2);

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
