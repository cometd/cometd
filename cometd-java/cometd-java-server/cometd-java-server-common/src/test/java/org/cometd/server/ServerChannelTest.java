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
package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServerChannelTest {
    private BayeuxChannelListener _bayeuxChannelListener;
    private BayeuxSubscriptionListener _bayeuxSubscriptionListener;
    private BayeuxServerImpl _bayeux;

    @Before
    public void init() throws Exception {
        _bayeuxChannelListener = new BayeuxChannelListener();
        _bayeuxSubscriptionListener = new BayeuxSubscriptionListener();
        _bayeux = new BayeuxServerImpl();
        _bayeux.start();
        _bayeux.addListener(_bayeuxChannelListener);
        _bayeux.addListener(_bayeuxSubscriptionListener);
    }

    @After
    public void destroy() throws Exception {
        _bayeux.removeListener(_bayeuxSubscriptionListener);
        _bayeux.removeListener(_bayeuxChannelListener);
        _bayeux.stop();
    }

    @Test
    public void testChannelCreate() throws Exception {
        Assert.assertNull(_bayeux.getChannel("/foo"));
        Assert.assertNull(_bayeux.getChannel("/foo/bar"));

        _bayeux.createChannelIfAbsent("/foo/bar");

        Assert.assertNull(_bayeux.getChannel("/foo"));
        ServerChannel fooBar = _bayeux.getChannel("/foo/bar");
        Assert.assertNotNull(fooBar);
        Assert.assertEquals(2, _bayeuxChannelListener._calls);
        Assert.assertEquals("initadded", _bayeuxChannelListener._method);
        Assert.assertEquals("/foo/bar", _bayeuxChannelListener._channel);

        // Same channel with trailing slash.
        MarkedReference<ServerChannel> channelRef = _bayeux.createChannelIfAbsent("/foo/bar/");
        Assert.assertFalse(channelRef.isMarked());
        Assert.assertSame(channelRef.getReference(), fooBar);

        // Same channel with trailing whitespace.
        channelRef = _bayeux.createChannelIfAbsent("/foo/bar/ ");
        Assert.assertFalse(channelRef.isMarked());
        Assert.assertSame(channelRef.getReference(), fooBar);

        _bayeux.createChannelIfAbsent("/foo/bob");

        Assert.assertNotNull(_bayeux.getChannel("/foo/bob"));
        Assert.assertEquals(4, _bayeuxChannelListener._calls);
        Assert.assertEquals("initadded", _bayeuxChannelListener._method);
        Assert.assertEquals("/foo/bob", _bayeuxChannelListener._channel);
    }

    @Test
    public void testCreateChildChannelAfterParent() throws Exception {
        final CountDownLatch latch = new CountDownLatch(2);
        String channelName = "/root";
        Assert.assertTrue(_bayeux.createChannelIfAbsent(channelName, (ConfigurableServerChannel.Initializer)channel -> {
            channel.setPersistent(true);
            latch.countDown();
        }).isMarked());
        Assert.assertTrue(_bayeux.createChannelIfAbsent(channelName + "/1", (ConfigurableServerChannel.Initializer)channel -> {
            channel.setPersistent(true);
            latch.countDown();
        }).isMarked());
        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testSubscribe() throws Exception {
        ServerChannelImpl fooBar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        ChannelSubscriptionListener csubl = new ChannelSubscriptionListener();
        fooBar.addListener(csubl);
        ServerSessionImpl session0 = newServerSession();

        fooBar.subscribe(session0);
        Assert.assertEquals(1, fooBar.getSubscribers().size());
        Assert.assertTrue(fooBar.getSubscribers().contains(session0));

        Assert.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assert.assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        Assert.assertEquals(session0, _bayeuxSubscriptionListener._session);

        Assert.assertEquals("subscribed", csubl._method);
        Assert.assertEquals(fooBar, csubl._channel);
        Assert.assertEquals(session0, csubl._session);

        // config+add for /foo/bar
        Assert.assertEquals(2, _bayeuxChannelListener._calls);

        ServerSessionImpl session1 = newServerSession();
        _bayeux.createChannelIfAbsent("/foo/*").getReference().subscribe(session1);

        Assert.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assert.assertEquals("/foo/*", _bayeuxSubscriptionListener._channel.getId());
        Assert.assertEquals(session1, _bayeuxSubscriptionListener._session);

        // config+add for /foo/*
        Assert.assertEquals(4, _bayeuxChannelListener._calls);

        ServerSessionImpl session2 = newServerSession();
        _bayeux.createChannelIfAbsent("/**").getReference().subscribe(session2);

        Assert.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assert.assertEquals("/**", _bayeuxSubscriptionListener._channel.getId());
        Assert.assertEquals(session2, _bayeuxSubscriptionListener._session);

        // config+add for /**
        Assert.assertEquals(6, _bayeuxChannelListener._calls);

        fooBar.unsubscribe(session0);
        Assert.assertEquals(0, fooBar.getSubscribers().size());
        Assert.assertFalse(fooBar.getSubscribers().contains(session0));

        Assert.assertEquals("unsubscribed", _bayeuxSubscriptionListener._method);
        Assert.assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        Assert.assertEquals(session0, _bayeuxSubscriptionListener._session);

        Assert.assertEquals("unsubscribed", csubl._method);
        Assert.assertEquals(fooBar, csubl._channel);
        Assert.assertEquals(session0, csubl._session);

        // Remove also the listener, then sweep: /foo/bar should be gone
        fooBar.removeListener(csubl);
        sweep();

        // remove for /foo/bar
        Assert.assertEquals(7, _bayeuxChannelListener._calls);
        Assert.assertEquals("/foo/bar", _bayeuxChannelListener._channel);
        Assert.assertEquals("removed", _bayeuxChannelListener._method);

        ServerChannelImpl fooBob = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bob").getReference();
        fooBob.subscribe(session0);
        ServerChannelImpl foo = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo").getReference();
        foo.subscribe(session0);
        foo.addListener(new ChannelSubscriptionListener());

        // config+add for /foo/bob and /foo
        Assert.assertEquals(11, _bayeuxChannelListener._calls);

        foo.remove();

        // removed for /foo
        Assert.assertEquals(12, _bayeuxChannelListener._calls);
        Assert.assertEquals("/foo", _bayeuxChannelListener._channel);
        Assert.assertEquals("removed", _bayeuxChannelListener._method);
        Assert.assertNull(_bayeux.getChannel("/foo"));
        Assert.assertEquals(0, foo.getSubscribers().size());
        Assert.assertEquals(0, foo.getListeners().size());
        Assert.assertNotNull(_bayeux.getChannel("/foo/bob"));
        Assert.assertEquals(1, fooBob.getSubscribers().size());
        Assert.assertNotNull(_bayeux.getChannel("/foo/*"));
    }

    @Test
    public void testUnSubscribeAll() throws Exception {
        ServerChannelImpl channel = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        ServerSessionImpl session0 = newServerSession();

        channel.subscribe(session0);
        Assert.assertEquals(1, channel.getSubscribers().size());
        Assert.assertTrue(channel.getSubscribers().contains(session0));

        _bayeux.removeServerSession(session0, false);

        Assert.assertEquals(0, channel.getSubscribers().size());
        Assert.assertTrue(!channel.getSubscribers().contains(session0));
    }

    @Test
    public void testPublish() throws Exception {
        _bayeux.start();

        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/*").getReference();
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/**").getReference();
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bob").getReference();
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/wibble").getReference();

        foobar.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return !"ignore".equals(message.getData());
            }
        });

        foostar.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                if ("foostar".equals(message.getData())) {
                    message.setData("FooStar");
                }
                return true;
            }
        });

        starstar.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                if ("starstar".equals(message.getData())) {
                    message.setData("StarStar");
                }
                return true;
            }
        });

        ServerSessionImpl session0 = newServerSession();

        // this is a private API - not a normal subscribe!!
        foobar.subscribe(session0);

        ServerSessionImpl session1 = newServerSession();
        foostar.subscribe(session1);
        ServerSessionImpl session2 = newServerSession();
        starstar.subscribe(session2);

        ServerMessage.Mutable msg = _bayeux.newMessage();
        msg.setData("Hello World");

        foobar.publish(session0, msg, Promise.noop());
        Assert.assertEquals(1, session0.getQueue().size());
        Assert.assertEquals(1, session1.getQueue().size());
        Assert.assertEquals(1, session2.getQueue().size());

        foobob.publish(session0, _bayeux.newMessage(msg), Promise.noop());
        Assert.assertEquals(1, session0.getQueue().size());
        Assert.assertEquals(2, session1.getQueue().size());
        Assert.assertEquals(2, session2.getQueue().size());

        wibble.publish(session0, _bayeux.newMessage(msg), Promise.noop());
        Assert.assertEquals(1, session0.getQueue().size());
        Assert.assertEquals(2, session1.getQueue().size());
        Assert.assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setData("ignore");
        foobar.publish(session0, msg, Promise.noop());
        Assert.assertEquals(1, session0.getQueue().size());
        Assert.assertEquals(2, session1.getQueue().size());
        Assert.assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setChannel("/lazy");
        msg.setData("foostar");
        msg.setLazy(true);
        foobar.publish(session0, msg, Promise.noop());
        Assert.assertEquals(2, session0.getQueue().size());
        Assert.assertEquals(3, session1.getQueue().size());
        Assert.assertEquals(4, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setChannel("/lazy");
        msg.setData("starstar");
        msg.setLazy(true);
        foobar.publish(session0, msg, Promise.noop());
        Assert.assertEquals(3, session0.getQueue().size());
        Assert.assertEquals(4, session1.getQueue().size());
        Assert.assertEquals(5, session2.getQueue().size());

        Assert.assertEquals("Hello World", session0.getQueue().poll().getData());
        Assert.assertEquals("FooStar", session0.getQueue().poll().getData());
        Assert.assertEquals("StarStar", session0.getQueue().poll().getData());
    }

    @Test
    public void testPublishFromSweptChannelSucceeds() throws Exception {
        _bayeux.start();

        ServerChannelImpl fooStarStar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/**").getReference();

        ServerSessionImpl session1 = newServerSession();
        fooStarStar.subscribe(session1);

        ServerChannel fooBar = _bayeux.createChannelIfAbsent("/foo/bar").getReference();

        sweep();

        Assert.assertNull(_bayeux.getChannel(fooBar.getId()));

        ServerSessionImpl session0 = newServerSession();
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setData("test");
        fooBar.publish(session0, message, Promise.noop());

        Assert.assertEquals(1, session1.getQueue().size());
    }

    @Test
    public void testPersistentChannelIsNotSwept() throws Exception {
        String channelName = "/foo/bar";
        ServerChannel foobar = _bayeux.createChannelIfAbsent(channelName).getReference();
        foobar.setPersistent(true);

        sweep();
        Assert.assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelWithSubscriberIsNotSwept() throws Exception {
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        Assert.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        // First sweep does not remove the channel yet
        _bayeux.sweep();
        Assert.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Nor a second sweep
        _bayeux.sweep();
        Assert.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Third sweep removes it
        _bayeux.sweep();
        Assert.assertNull(_bayeux.getChannel("/foo/bar"));

        _bayeux.createChannelIfAbsent("/foo/bar/baz").getReference().remove();
        Assert.assertNull(_bayeux.getChannel("/foo/bar/baz"));
        Assert.assertNull(_bayeux.getChannel("/foo/bar"));
        Assert.assertNull(_bayeux.getChannel("/foo"));

        foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        Assert.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        ServerChannelImpl foobarbaz = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar/baz").getReference();
        ServerSessionImpl session0 = newServerSession();
        foobarbaz.subscribe(session0);
        _bayeux.createChannelIfAbsent("/foo").getReference().subscribe(session0);

        sweep();
        Assert.assertNotNull(_bayeux.getChannel("/foo/bar/baz"));
        Assert.assertNull(_bayeux.getChannel("/foo/bar"));
        Assert.assertNotNull(_bayeux.getChannel("/foo"));

        foobarbaz.unsubscribe(session0);

        sweep();
        Assert.assertNull(_bayeux.getChannel("/foo/bar/baz"));
        Assert.assertNull(_bayeux.getChannel("/foo/bar"));
        Assert.assertNotNull(_bayeux.getChannel("/foo"));

        _bayeux.getChannel("/foo").unsubscribe(session0);

        sweep();
        Assert.assertNull(_bayeux.getChannel("/foo"));
    }

    @Test
    public void testChannelWithListenersIsNotSwept() throws Exception {
        String channelName = "/test";
        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName).getReference();
        channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return true;
            }
        });

        sweep();

        Assert.assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelsWithAutorizersSweeping() throws Exception {
        ServerChannel.MessageListener listener = new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return true;
            }
        };
        ConfigurableServerChannel.Initializer initializer = channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);

        String channelName1 = "/a/b/c";
        ServerChannel channel1 = _bayeux.createChannelIfAbsent(channelName1).getReference();
        channel1.addListener(listener);

        String wildName1 = "/a/b/*";
        _bayeux.createChannelIfAbsent(wildName1, initializer);

        String wildName2 = "/a/**";
        _bayeux.createChannelIfAbsent(wildName2, initializer);

        sweep();

        // Channel with authorizers but no listeners or subscriber must not be swept
        Assert.assertNotNull(_bayeux.getChannel(channelName1));
        Assert.assertNotNull(_bayeux.getChannel(wildName1));
        Assert.assertNotNull(_bayeux.getChannel(wildName2));

        // Remove the authorizer from a wild parent must sweep the wild parent
        _bayeux.getChannel(wildName2).removeAuthorizer(GrantAuthorizer.GRANT_ALL);

        sweep();

        Assert.assertNotNull(_bayeux.getChannel(channelName1));
        Assert.assertNotNull(_bayeux.getChannel(wildName1));
        Assert.assertNull(_bayeux.getChannel(wildName2));

        // Remove the listener from a channel must not sweep the wild parent with authorizer
        // since other channels may be added later that will match the wild channel
        _bayeux.getChannel(channelName1).removeListener(listener);

        sweep();

        Assert.assertNull(_bayeux.getChannel(channelName1));
        Assert.assertNotNull(_bayeux.getChannel(wildName1));
        Assert.assertNull(_bayeux.getChannel(wildName2));
    }

    @Test
    public void testSweepOfWeakListeners() {
        class L implements ServerChannel.MessageListener {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return true;
            }
        }
        class W extends L implements ServerChannel.ServerChannelListener.Weak {
        }

        final ServerChannel.ServerChannelListener listener = new L();
        final String channelName = "/weak";
        _bayeux.createChannelIfAbsent(channelName, (ConfigurableServerChannel.Initializer)channel -> {
            channel.addListener(listener);
            channel.addListener(new W());
        });

        sweep();

        // Non-weak listener present, must not be swept
        Assert.assertNotNull(_bayeux.getChannel(channelName));

        _bayeux.getChannel(channelName).removeListener(listener);
        sweep();

        // Only weak listeners present, must be swept
        Assert.assertNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testLazyTimeout() throws Exception {
        String channelName = "/testLazy";
        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer.Persistent()).getReference();
        Assert.assertFalse(channel.isLazy());

        int lazyTimeout = 1000;
        channel.setLazyTimeout(lazyTimeout);
        Assert.assertTrue(channel.isLazy());

        channel.setLazy(true);
        Assert.assertEquals(lazyTimeout, channel.getLazyTimeout());

        channel.setLazy(false);
        Assert.assertFalse(channel.isLazy());
        Assert.assertEquals(-1, channel.getLazyTimeout());

        channel.setLazy(true);
        Assert.assertTrue(channel.isLazy());
        Assert.assertEquals(-1, channel.getLazyTimeout());
    }

    private void sweep() {
        // 12 is a big enough number that will make sure channel will be swept
        for (int i = 0; i < 12; ++i) {
            _bayeux.sweep();
        }
    }

    private ServerSessionImpl newServerSession() {
        ServerSessionImpl session = _bayeux.newServerSession();
        _bayeux.addServerSession(session, _bayeux.newMessage());
        session.handshake(null);
        session.connected();
        return session;
    }

    static class BayeuxSubscriptionListener implements BayeuxServer.SubscriptionListener {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;

        public void reset() {
            _method = null;
            _session = null;
            _channel = null;
        }

        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _method = "subscribed";
            _session = session;
            _channel = channel;
        }

        @Override
        public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _method = "unsubscribed";
            _session = session;
            _channel = channel;
        }
    }

    static class ChannelSubscriptionListener implements ServerChannel.SubscriptionListener {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;

        public void reset() {
            _method = null;
            _session = null;
            _channel = null;
        }

        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _method = "subscribed";
            _session = session;
            _channel = channel;
        }

        @Override
        public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _method = "unsubscribed";
            _session = session;
            _channel = channel;
        }
    }

    static class BayeuxChannelListener implements BayeuxServer.ChannelListener {
        public int _calls;
        public String _method;
        public String _channel;

        public void reset() {
            _calls = 0;
            _method = null;
            _channel = null;
        }

        @Override
        public void configureChannel(ConfigurableServerChannel channel) {
            _calls++;
            _method = "init";
        }

        @Override
        public void channelAdded(ServerChannel channel) {
            _calls++;
            _method += "added";
            _channel = channel.getId();
        }

        @Override
        public void channelRemoved(String channelId) {
            _calls++;
            _method = "removed";
            _channel = channelId;
        }
    }
}
