package org.cometd.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import junit.framework.Assert;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;



public class BayeuxServerTest extends Assert
{
    private final Queue<Object> _events = new ConcurrentLinkedQueue<Object>();
    private final BayeuxServerImpl _bayeux = new BayeuxServerImpl();

    private ServerSessionImpl newServerSession()
    {
        ServerSessionImpl session = _bayeux.newServerSession();
        _bayeux.addServerSession(session);
        session.handshake();
        session.connect();
        return session;
    }

    @Before
    public void init() throws Exception
    {
        _bayeux.start();
    }

    @After
    public void destroy() throws Exception
    {
        _bayeux.stop();
        _events.clear();
    }

    @Test
    public void testListeners() throws Exception
    {
        _bayeux.addListener(new SubListener());
        _bayeux.addListener(new SessListener());
        _bayeux.addListener(new CListener());

        String channelName = "/foo/bar";
        _bayeux.createIfAbsent(channelName);
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.getChannel(channelName);
        channelName = "/foo/*";
        _bayeux.createIfAbsent(channelName);
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.getChannel(channelName);
        channelName = "/**";
        _bayeux.createIfAbsent(channelName);
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.getChannel(channelName);
        channelName = "/foo/bob";
        _bayeux.createIfAbsent(channelName);
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.getChannel(channelName);
        channelName = "/wibble";
        _bayeux.createIfAbsent(channelName);
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.getChannel(channelName);

        assertEquals("channelAdded",_events.poll());
        assertEquals(_bayeux.getChannel("/foo"),_events.poll());
        assertEquals("channelAdded",_events.poll());
        assertEquals(foobar,_events.poll());
        assertEquals("channelAdded",_events.poll());
        assertEquals(foostar,_events.poll());
        assertEquals("channelAdded",_events.poll());
        assertEquals(starstar,_events.poll());
        assertEquals("channelAdded",_events.poll());
        assertEquals(foobob,_events.poll());
        assertEquals("channelAdded",_events.poll());
        assertEquals(wibble,_events.poll());

        wibble.remove();
        assertEquals("channelRemoved",_events.poll());
        assertEquals(wibble.getId(),_events.poll());

        ServerSessionImpl session0 = newServerSession();
        ServerSessionImpl session1 = newServerSession();
        ServerSessionImpl session2 = newServerSession();

        assertEquals("sessionAdded",_events.poll());
        assertEquals(session0,_events.poll());
        assertEquals("sessionAdded",_events.poll());
        assertEquals(session1,_events.poll());
        assertEquals("sessionAdded",_events.poll());
        assertEquals(session2,_events.poll());

        foobar.subscribe(session0);
        foobar.unsubscribe(session0);

        assertEquals("subscribed",_events.poll());
        assertEquals(session0,_events.poll());
        assertEquals(foobar,_events.poll());
        assertEquals("unsubscribed",_events.poll());
        assertEquals(session0,_events.poll());
        assertEquals(foobar,_events.poll());
    }


    @Test
    public void testSessionAttributes() throws Exception
    {
        LocalSession local = _bayeux.newLocalSession("s0");
        local.handshake();
        ServerSession session = local.getServerSession();

        local.setAttribute("foo","bar");
        assertEquals("bar",local.getAttribute("foo"));
        assertEquals(null,session.getAttribute("foo"));

        session.setAttribute("bar","foo");
        assertEquals(null,local.getAttribute("bar"));
        assertEquals("foo",session.getAttribute("bar"));

        assertTrue(local.getAttributeNames().contains("foo"));
        assertFalse(local.getAttributeNames().contains("bar"));
        assertFalse(session.getAttributeNames().contains("foo"));
        assertTrue(session.getAttributeNames().contains("bar"));

        assertEquals("bar",local.removeAttribute("foo"));
        assertEquals(null,local.removeAttribute("foo"));
        assertEquals("foo",session.removeAttribute("bar"));
        assertEquals(null,local.removeAttribute("bar"));

    }

    @Test
    public void testLocalSessions() throws Exception
    {
        LocalSession session0 = _bayeux.newLocalSession("s0");
        assertTrue(session0.toString().indexOf("s0?")>=0);
        session0.handshake();
        assertTrue(session0.toString().indexOf("s0_")>=0);

        final LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();
        final LocalSession session2 = _bayeux.newLocalSession("s2");
        session2.handshake();

        final Queue<String> events = new ConcurrentLinkedQueue<String>();

        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                events.add(channel.getSession().getId());
                events.add(message.getData().toString());
            }
        };

        session0.getChannel("/foo/bar").subscribe(listener);
        session0.getChannel("/foo/bar").subscribe(listener);
        session1.getChannel("/foo/bar").subscribe(listener);
        session2.getChannel("/foo/bar").subscribe(listener);

        System.err.println(_bayeux.dump());

        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());

        session0.getChannel("/foo/bar").unsubscribe(listener);
        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        session0.getChannel("/foo/bar").unsubscribe(listener);
        assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());

        ClientSessionChannel foobar0=session0.getChannel("/foo/bar");
        foobar0.subscribe(listener);
        foobar0.subscribe(listener);

        ClientSessionChannel foostar0=session0.getChannel("/foo/*");
        foostar0.subscribe(listener);

        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        assertEquals(session0,foobar0.getSession());
        assertEquals("/foo/bar",foobar0.getId());
        assertEquals(false,foobar0.isDeepWild());
        assertEquals(false,foobar0.isWild());
        assertEquals(false,foobar0.isMeta());
        assertEquals(false,foobar0.isService());

        foobar0.publish("hello");

        assertEquals(session0.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session1.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session2.getId(),events.poll());
        assertEquals("hello",events.poll());
        foostar0.unsubscribe(listener);

        session1.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel foobar1=session1.getChannel("/foo/bar");
                foobar1.publish("part1");
                assertEquals(null,events.poll());
                foobar1.publish("part2");
            }
        });

        assertEquals(session1.getId(),events.poll());
        assertEquals("part1",events.poll());
        assertEquals(session2.getId(),events.poll());
        assertEquals("part1",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("part1",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("part1",events.poll());
        assertEquals(session1.getId(),events.poll());
        assertEquals("part2",events.poll());
        assertEquals(session2.getId(),events.poll());
        assertEquals("part2",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("part2",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("part2",events.poll());


        foobar0.unsubscribe();
        assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());




        assertTrue(session0.isConnected());
        assertTrue(session1.isConnected());
        assertTrue(session2.isConnected());
        ServerSession ss0=session0.getServerSession();
        ServerSession ss1=session1.getServerSession();
        ServerSession ss2=session2.getServerSession();
        assertTrue(ss0.isConnected());
        assertTrue(ss1.isConnected());
        assertTrue(ss2.isConnected());

        session0.disconnect();
        assertFalse(session0.isConnected());
        assertFalse(ss0.isConnected());

        session1.getServerSession().disconnect();
        assertFalse(session1.isConnected());
        assertFalse(ss1.isConnected());

        session2.getServerSession().disconnect();
        assertFalse(session2.isConnected());
        assertFalse(ss2.isConnected());
    }

    @Test
    public void testExtensions() throws Exception
    {
        final Queue<String> events = new ConcurrentLinkedQueue<String>();
        _bayeux.addExtension(new BayeuxServer.Extension()
        {
            public boolean sendMeta(ServerSession to, Mutable message)
            {
                return true;
            }

            public boolean send(ServerSession from, ServerSession to, Mutable message)
            {
                if ("three".equals(message.getData()))
                    message.setData("four");
                return !"ignoreSend".equals(message.getData());
            }

            public boolean rcvMeta(ServerSession from, Mutable message)
            {
                return true;
            }

            public boolean rcv(ServerSession from, Mutable message)
            {
                if ("one".equals(message.getData()))
                    message.setData("two");
                return !"ignoreRcv".equals(message.getData());
            }
        });

        final LocalSession session0 = _bayeux.newLocalSession("s0");
        session0.handshake();
        //final LocalSession session1 = _bayeux.newLocalSession("s1");
        //session1.handshake();

        session0.addExtension(new ClientSession.Extension()
        {
            public boolean sendMeta(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                return true;
            }

            public boolean send(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                if ("zero".equals(message.getData()))
                    message.setData("one");
                return true;
            }

            public boolean rcvMeta(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                return true;
            }

            public boolean rcv(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                if ("five".equals(message.getData()))
                    message.setData("six");
                return true;
            }
        });


        session0.getServerSession().addExtension(new ServerSession.Extension()
        {
            public boolean rcv(ServerSession from, Mutable message)
            {
                if ("two".equals(message.getData()))
                    message.setData("three");
                return true;
            }

            public boolean rcvMeta(ServerSession from, Mutable message)
            {
                return true;
            }

            public ServerMessage send(ServerSession to, ServerMessage message)
            {
                if (message.isMeta())
                    new Throwable().printStackTrace();
                if ("four".equals(message.getData()))
                {
                    ServerMessage.Mutable cloned=_bayeux.newMessage(message);
                    cloned.setData("five");
                    return cloned;
                }
                return message;
            }

            public boolean sendMeta(ServerSession to, Mutable message)
            {
                return true;
            }
        });

        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                events.add(channel.getSession().getId());
                events.add(message.getData().toString());
            }
        };

        session0.getChannel("/foo/bar").subscribe(listener);
        // session1.getChannel("/foo/bar").subscribe(listener);

        session0.getChannel("/foo/bar").publish("zero");
        session0.getChannel("/foo/bar").publish("ignoreSend");
        session0.getChannel("/foo/bar").publish("ignoreRcv");

        Thread.sleep(100);
        System.err.println(events);

        assertEquals(session0.getId(),events.poll());
        assertEquals("six",events.poll());

        /*
        assertEquals(session1.getId(),events.poll());
        assertEquals("four",events.poll());
        assertEquals(null,events.poll());
        */

    }

    class CListener implements BayeuxServer.ChannelListener
    {
        public void configureChannel(ConfigurableServerChannel channel)
        {
        }

        public void channelAdded(ServerChannel channel)
        {
            _events.add("channelAdded");
            _events.add(channel);
        }

        public void channelRemoved(String channelId)
        {
            _events.add("channelRemoved");
            _events.add(channelId);
        }

    }

    class SessListener implements BayeuxServer.SessionListener
    {
        public void sessionAdded(ServerSession session)
        {
            _events.add("sessionAdded");
            _events.add(session);
        }

        public void sessionRemoved(ServerSession session, boolean timedout)
        {
            _events.add("sessionRemoved");
            _events.add(session);
            _events.add(timedout);
        }
    }

    class SubListener implements BayeuxServer.SubscriptionListener
    {
        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _events.add("subscribed");
            _events.add(session);
            _events.add(channel);
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _events.add("unsubscribed");
            _events.add(session);
            _events.add(channel);
        }

    }



}
