package org.cometd.server;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ServerChannelTest
{
    private BayeuxChannelListener _bayeuxChannelListener;
    private BayeuxSubscriptionListener _bayeuxSubscriptionListener;
    private BayeuxServerImpl _bayeux;

    @Before
    public void init() throws Exception
    {
        _bayeuxChannelListener = new BayeuxChannelListener();
        _bayeuxSubscriptionListener = new BayeuxSubscriptionListener();
        _bayeux = new BayeuxServerImpl();

        _bayeux.addListener(_bayeuxChannelListener);
        _bayeux.addListener(_bayeuxSubscriptionListener);
    }

    @After
    public void destroy() throws Exception
    {
        _bayeux.removeListener(_bayeuxSubscriptionListener);
        _bayeux.removeListener(_bayeuxChannelListener);
    }

    @Test
    public void testChannelCreate() throws Exception
    {
        assertTrue(_bayeux.getChannel("/foo") == null);
        assertTrue(_bayeux.getChannel("/foo/bar") == null);
        _bayeux.createIfAbsent("/foo/bar");
        _bayeux.getChannel("/foo/bar");
        assertTrue(_bayeux.getChannel("/foo") != null);
        assertTrue(_bayeux.getChannel("/foo/bar") != null);
        assertEquals(4, _bayeuxChannelListener._calls);
        assertEquals("initadded", _bayeuxChannelListener._method);
        assertEquals("/foo/bar", _bayeuxChannelListener._channel);
        _bayeux.createIfAbsent("/foo/bob");
        assertTrue(_bayeux.getChannel("/foo/bob") != null);
        assertEquals(6, _bayeuxChannelListener._calls);
        assertEquals("initadded", _bayeuxChannelListener._method);
        assertEquals("/foo/bob", _bayeuxChannelListener._channel);
    }

    @Test
    public void testSubscribe() throws Exception
    {
        _bayeux.createIfAbsent("/foo/bar");
        ServerChannelImpl fooBar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar");
        ChannelSubscriptionListener csubl = new ChannelSubscriptionListener();
        fooBar.addListener(csubl);
        ServerSessionImpl session0 = newServerSession();

        fooBar.subscribe(session0);
        assertEquals(1, fooBar.getSubscribers().size());
        assertTrue(fooBar.getSubscribers().contains(session0));

        assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        assertEquals(session0, _bayeuxSubscriptionListener._session);

        assertEquals("subscribed", csubl._method);
        assertEquals(fooBar, csubl._channel);
        assertEquals(session0, csubl._session);

        // config+add for /foo and config+add for /foo/bar
        assertEquals(4, _bayeuxChannelListener._calls);

        ServerSessionImpl session1 = newServerSession();
        _bayeux.createIfAbsent("/foo/*");
        ((ServerChannelImpl)_bayeux.getChannel("/foo/*")).subscribe(session1);

        assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        assertEquals("/foo/*", _bayeuxSubscriptionListener._channel.getId());
        assertEquals(session1, _bayeuxSubscriptionListener._session);

        // config+add for /foo/*
        assertEquals(6, _bayeuxChannelListener._calls);

        ServerSessionImpl session2 = newServerSession();
        _bayeux.createIfAbsent("/**");
        ((ServerChannelImpl)_bayeux.getChannel("/**")).subscribe(session2);

        assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        assertEquals("/**", _bayeuxSubscriptionListener._channel.getId());
        assertEquals(session2, _bayeuxSubscriptionListener._session);

        // config+add for /**
        assertEquals(8, _bayeuxChannelListener._calls);

        fooBar.unsubscribe(session0);
        assertEquals(0, fooBar.getSubscribers().size());
        assertFalse(fooBar.getSubscribers().contains(session0));

        assertEquals("unsubscribed", _bayeuxSubscriptionListener._method);
        assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        assertEquals(session0, _bayeuxSubscriptionListener._session);

        assertEquals("unsubscribed", csubl._method);
        assertEquals(fooBar, csubl._channel);
        assertEquals(session0, csubl._session);

        // Remove also the listener, then sweep: /foo/bar should be gone
        fooBar.removeListener(csubl);
        sweep();

        // remove for /foo/bar
        assertEquals(9, _bayeuxChannelListener._calls);
        assertEquals("/foo/bar", _bayeuxChannelListener._channel);
        assertEquals("removed", _bayeuxChannelListener._method);

        _bayeux.createIfAbsent("/foo/bob");
        ServerChannelImpl fooBob = (ServerChannelImpl)_bayeux.getChannel("/foo/bob");
        fooBob.subscribe(session0);
        ServerChannelImpl foo = (ServerChannelImpl)_bayeux.getChannel("/foo");
        foo.subscribe(session0);
        foo.addListener(new ChannelSubscriptionListener());

        // config+add for /foo/bob
        assertEquals(11, _bayeuxChannelListener._calls);

        foo.remove();

        // removed for /foo/bob, /foo/* and /foo
        assertEquals(14, _bayeuxChannelListener._calls);
        assertEquals("/foo", _bayeuxChannelListener._channel);
        assertEquals("removed", _bayeuxChannelListener._method);

        assertEquals(0, foo.getSubscribers().size());
        assertEquals(0, foo.getListeners().size());
        assertEquals(0, fooBob.getSubscribers().size());
    }

    @Test
    public void testUnSubscribeAll() throws Exception
    {
        _bayeux.createIfAbsent("/foo/bar");
        ServerChannelImpl channel = (ServerChannelImpl)_bayeux.getChannel("/foo/bar");
        ServerSessionImpl session0 = newServerSession();

        channel.subscribe(session0);
        assertEquals(1, channel.getSubscribers().size());
        assertTrue(channel.getSubscribers().contains(session0));

        _bayeux.removeServerSession(session0, false);

        assertEquals(0, channel.getSubscribers().size());
        assertTrue(!channel.getSubscribers().contains(session0));
    }

    @Test
    public void testPublish() throws Exception
    {
        _bayeux.createIfAbsent("/foo/bar");
        _bayeux.createIfAbsent("/foo/*");
        _bayeux.createIfAbsent("/**");
        _bayeux.createIfAbsent("/foo/bob");
        _bayeux.createIfAbsent("/wibble");

        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar");
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.getChannel("/foo/*");
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.getChannel("/**");
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.getChannel("/foo/bob");
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.getChannel("/wibble");

        foobar.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                return !"ignore".equals(message.getData());
            }
        });

        foostar.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                if ("foostar".equals(message.getData()))
                    message.setData("FooStar");
                return true;
            }
        });

        starstar.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                if ("starstar".equals(message.getData()))
                    message.setData("StarStar");
                return true;
            }
        });

        ServerSessionImpl session0 = newServerSession();

        // this is a private API - not a normal subscribe!!
        foobar.subscribe(session0);

        ServerSessionImpl session1 = newServerSession();
        foostar.subscribe(session1);
        ServerSessionImpl session2 = newServerSession();
        _bayeux.addServerSession(session2);
        session2.connect();
        starstar.subscribe(session2);

        ServerMessage.Mutable msg = _bayeux.newMessage();
        msg.setData("Hello World");

        foobar.publish(session0, msg);
        assertEquals(1, session0.getQueue().size());
        assertEquals(1, session1.getQueue().size());
        assertEquals(1, session2.getQueue().size());

        foobob.publish(session0, _bayeux.newMessage(msg));
        assertEquals(1, session0.getQueue().size());
        assertEquals(2, session1.getQueue().size());
        assertEquals(2, session2.getQueue().size());

        wibble.publish(session0, _bayeux.newMessage(msg));
        assertEquals(1, session0.getQueue().size());
        assertEquals(2, session1.getQueue().size());
        assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setData("ignore");
        foobar.publish(session0, msg);
        assertEquals(1, session0.getQueue().size());
        assertEquals(2, session1.getQueue().size());
        assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setData("foostar");
        msg.setLazy(true);
        foobar.publish(session0, msg);
        assertEquals(2, session0.getQueue().size());
        assertEquals(3, session1.getQueue().size());
        assertEquals(4, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setData("starstar");
        msg.setLazy(true);
        foobar.publish(session0, msg);
        assertEquals(3, session0.getQueue().size());
        assertEquals(4, session1.getQueue().size());
        assertEquals(5, session2.getQueue().size());

        assertEquals("Hello World", session0.getQueue().poll().getData());
        assertEquals("FooStar", session0.getQueue().poll().getData());
        assertEquals("StarStar", session0.getQueue().poll().getData());
    }

    @Test
    public void testPublishFromSweptChannelSucceeds() throws Exception
    {
        _bayeux.createIfAbsent("/foo/**");
        ServerChannelImpl fooStarStar = (ServerChannelImpl)_bayeux.getChannel("/foo/**");

        ServerSessionImpl session1 = newServerSession();
        fooStarStar.subscribe(session1);

        _bayeux.createIfAbsent("/foo/bar");
        ServerChannel fooBar = _bayeux.getChannel("/foo/bar");

        sweep();

        assertNull(_bayeux.getChannel(fooBar.getId()));

        ServerSessionImpl session0 = newServerSession();
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setData("test");
        fooBar.publish(session0, message);

        assertEquals(1, session1.getQueue().size());
    }

    @Test
    public void testPersistentChannelIsNotSwept() throws Exception
    {
        String channelName = "/foo/bar";
        _bayeux.createIfAbsent(channelName);
        ServerChannel foobar = _bayeux.getChannel(channelName);
        foobar.setPersistent(true);

        sweep();
        assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelWithSubscriberIsNotSwept() throws Exception
    {
        _bayeux.createIfAbsent("/foo/bar");
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar");
        assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        // First sweep does not remove the channel yet
        _bayeux.doSweep();
        assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Nor a second sweep
        _bayeux.doSweep();
        assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Third sweep removes it
        _bayeux.doSweep();
        assertNull(_bayeux.getChannel("/foo/bar"));

        _bayeux.createIfAbsent("/foo/bar/baz");
        _bayeux.getChannel("/foo/bar/baz").remove();
        assertNull(_bayeux.getChannel("/foo/bar/baz"));
        assertNotNull(_bayeux.getChannel("/foo/bar"));
        assertNotNull(_bayeux.getChannel("/foo"));

        sweep();
        assertNull(_bayeux.getChannel("/foo/bar"));
        assertNull(_bayeux.getChannel("/foo"));

        _bayeux.createIfAbsent("/foo/bar");
        foobar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar");
        assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        _bayeux.createIfAbsent("/foo/bar/baz");
        ServerChannelImpl foobarbaz = (ServerChannelImpl)_bayeux.getChannel("/foo/bar/baz");
        ServerSessionImpl session0 = newServerSession();
        foobarbaz.subscribe(session0);
        ((ServerChannelImpl)_bayeux.getChannel("/foo")).subscribe(session0);

        sweep();
        assertNotNull(_bayeux.getChannel("/foo/bar/baz"));
        assertNotNull(_bayeux.getChannel("/foo/bar"));
        assertNotNull(_bayeux.getChannel("/foo"));

        foobarbaz.unsubscribe(session0);

        sweep();
        assertNull(_bayeux.getChannel("/foo/bar/baz"));
        assertNull(_bayeux.getChannel("/foo/bar"));
        assertNotNull(_bayeux.getChannel("/foo"));

        ((ServerChannelImpl)_bayeux.getChannel("/foo")).unsubscribe(session0);

        sweep();
        assertNull(_bayeux.getChannel("/foo"));
    }

    @Test
    public void testChannelWithListenersIsNotSwept() throws Exception
    {
        String channelName = "/test";
        _bayeux.createIfAbsent(channelName);
        ServerChannel channel = _bayeux.getChannel(channelName);
        channel.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                return true;
            }
        });

        sweep();

        assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelsWithAutorizersSweeping() throws Exception
    {
        ServerChannel.MessageListener listener = new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                return true;
            }
        };
        ConfigurableServerChannel.Initializer initializer = new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        };

        String channelName1 = "/a/b/c";
        _bayeux.createIfAbsent(channelName1);
        ServerChannel channel1 = _bayeux.getChannel(channelName1);
        channel1.addListener(listener);

        String wildName1 = "/a/b/*";
        _bayeux.createIfAbsent(wildName1, initializer);

        String wildName2 = "/a/**";
        _bayeux.createIfAbsent(wildName2, initializer);

        sweep();

        // Channel with authorizers but no listeners or subscriber must not be swept
        assertNotNull(_bayeux.getChannel(channelName1));
        assertNotNull(_bayeux.getChannel(wildName1));
        assertNotNull(_bayeux.getChannel(wildName2));

        // Remove the authorizer from a wild parent must sweep the wild parent
        _bayeux.getChannel(wildName2).removeAuthorizer(GrantAuthorizer.GRANT_ALL);

        sweep();

        assertNotNull(_bayeux.getChannel(channelName1));
        assertNotNull(_bayeux.getChannel(wildName1));
        assertNull(_bayeux.getChannel(wildName2));

        // Remove the listener from a channel must not sweep the wild parent with authorizer
        // since other channels may be added later that will match the wild channel
        _bayeux.getChannel(channelName1).removeListener(listener);

        sweep();

        assertNull(_bayeux.getChannel(channelName1));
        assertNotNull(_bayeux.getChannel(wildName1));
        assertNull(_bayeux.getChannel(wildName2));
    }

    private void sweep()
    {
        // 12 is a big enough number that will make sure channel will be swept
        for (int i = 0; i < 12; ++i)
            _bayeux.doSweep();
    }

    private ServerSessionImpl newServerSession()
    {
        ServerSessionImpl session = _bayeux.newServerSession();
        _bayeux.addServerSession(session);
        session.handshake();
        session.connect();
        return session;
    }

    static class BayeuxSubscriptionListener implements BayeuxServer.SubscriptionListener
    {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;

        public void reset()
        {
            _method = null;
            _session = null;
            _channel = null;
        }

        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _method = "subscribed";
            _session = session;
            _channel = channel;
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _method = "unsubscribed";
            _session = session;
            _channel = channel;
        }
    }

    static class ChannelSubscriptionListener implements ServerChannel.SubscriptionListener
    {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;

        public void reset()
        {
            _method = null;
            _session = null;
            _channel = null;
        }

        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _method = "subscribed";
            _session = session;
            _channel = channel;
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _method = "unsubscribed";
            _session = session;
            _channel = channel;
        }
    }

    static class BayeuxChannelListener implements BayeuxServer.ChannelListener
    {
        public int _calls;
        public String _method;
        public String _channel;

        public void reset()
        {
            _calls = 0;
            _method = null;
            _channel = null;
        }

        public void configureChannel(ConfigurableServerChannel channel)
        {
            _calls++;
            _method = "init";
        }

        public void channelAdded(ServerChannel channel)
        {
            _calls++;
            _method += "added";
            _channel = channel.getId();
        }

        public void channelRemoved(String channelId)
        {
            _calls++;
            _method = "removed";
            _channel = channelId;
        }
    }
}
