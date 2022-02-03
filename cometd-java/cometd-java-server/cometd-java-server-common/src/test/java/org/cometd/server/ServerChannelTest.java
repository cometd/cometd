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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServerChannelTest {
    private BayeuxChannelListener _bayeuxChannelListener;
    private BayeuxSubscriptionListener _bayeuxSubscriptionListener;
    private BayeuxServerImpl _bayeux;

    @BeforeEach
    public void init() throws Exception {
        _bayeuxChannelListener = new BayeuxChannelListener();
        _bayeuxSubscriptionListener = new BayeuxSubscriptionListener();
        _bayeux = new BayeuxServerImpl();
        _bayeux.start();
        _bayeux.addListener(_bayeuxChannelListener);
        _bayeux.addListener(_bayeuxSubscriptionListener);
    }

    @AfterEach
    public void destroy() throws Exception {
        _bayeux.removeListener(_bayeuxSubscriptionListener);
        _bayeux.removeListener(_bayeuxChannelListener);
        _bayeux.stop();
    }

    @Test
    public void testChannelCreate() {
        Assertions.assertNull(_bayeux.getChannel("/foo"));
        Assertions.assertNull(_bayeux.getChannel("/foo/bar"));

        _bayeux.createChannelIfAbsent("/foo/bar");

        Assertions.assertNull(_bayeux.getChannel("/foo"));
        ServerChannel fooBar = _bayeux.getChannel("/foo/bar");
        Assertions.assertNotNull(fooBar);
        Assertions.assertEquals(2, _bayeuxChannelListener._calls);
        Assertions.assertEquals("initadded", _bayeuxChannelListener._method);
        Assertions.assertEquals("/foo/bar", _bayeuxChannelListener._channel);

        // Same channel with trailing slash.
        MarkedReference<ServerChannel> channelRef = _bayeux.createChannelIfAbsent("/foo/bar/");
        Assertions.assertFalse(channelRef.isMarked());
        Assertions.assertSame(channelRef.getReference(), fooBar);

        // Same channel with trailing whitespace.
        channelRef = _bayeux.createChannelIfAbsent("/foo/bar/ ");
        Assertions.assertFalse(channelRef.isMarked());
        Assertions.assertSame(channelRef.getReference(), fooBar);

        _bayeux.createChannelIfAbsent("/foo/bob");

        Assertions.assertNotNull(_bayeux.getChannel("/foo/bob"));
        Assertions.assertEquals(4, _bayeuxChannelListener._calls);
        Assertions.assertEquals("initadded", _bayeuxChannelListener._method);
        Assertions.assertEquals("/foo/bob", _bayeuxChannelListener._channel);
    }

    @Test
    public void testCreateChildChannelAfterParent() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        String channelName = "/root";
        Assertions.assertTrue(_bayeux.createChannelIfAbsent(channelName, channel -> {
            channel.setPersistent(true);
            latch.countDown();
        }).isMarked());
        Assertions.assertTrue(_bayeux.createChannelIfAbsent(channelName + "/1", channel -> {
            channel.setPersistent(true);
            latch.countDown();
        }).isMarked());
        Assertions.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testSubscribe() {
        ServerChannelImpl fooBar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        ChannelSubscriptionListener csubl = new ChannelSubscriptionListener();
        fooBar.addListener(csubl);
        ServerSessionImpl session0 = newServerSession();

        fooBar.subscribe(session0);
        Assertions.assertEquals(1, fooBar.getSubscribers().size());
        Assertions.assertTrue(fooBar.getSubscribers().contains(session0));

        Assertions.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assertions.assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        Assertions.assertEquals(session0, _bayeuxSubscriptionListener._session);

        Assertions.assertEquals("subscribed", csubl._method);
        Assertions.assertEquals(fooBar, csubl._channel);
        Assertions.assertEquals(session0, csubl._session);

        // config+add for /foo/bar
        Assertions.assertEquals(2, _bayeuxChannelListener._calls);

        ServerSessionImpl session1 = newServerSession();
        _bayeux.createChannelIfAbsent("/foo/*").getReference().subscribe(session1);

        Assertions.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assertions.assertEquals("/foo/*", _bayeuxSubscriptionListener._channel.getId());
        Assertions.assertEquals(session1, _bayeuxSubscriptionListener._session);

        // config+add for /foo/*
        Assertions.assertEquals(4, _bayeuxChannelListener._calls);

        ServerSessionImpl session2 = newServerSession();
        _bayeux.createChannelIfAbsent("/**").getReference().subscribe(session2);

        Assertions.assertEquals("subscribed", _bayeuxSubscriptionListener._method);
        Assertions.assertEquals("/**", _bayeuxSubscriptionListener._channel.getId());
        Assertions.assertEquals(session2, _bayeuxSubscriptionListener._session);

        // config+add for /**
        Assertions.assertEquals(6, _bayeuxChannelListener._calls);

        fooBar.unsubscribe(session0);
        Assertions.assertEquals(0, fooBar.getSubscribers().size());
        Assertions.assertFalse(fooBar.getSubscribers().contains(session0));

        Assertions.assertEquals("unsubscribed", _bayeuxSubscriptionListener._method);
        Assertions.assertEquals(fooBar, _bayeuxSubscriptionListener._channel);
        Assertions.assertEquals(session0, _bayeuxSubscriptionListener._session);

        Assertions.assertEquals("unsubscribed", csubl._method);
        Assertions.assertEquals(fooBar, csubl._channel);
        Assertions.assertEquals(session0, csubl._session);

        // Remove also the listener, then sweep: /foo/bar should be gone
        fooBar.removeListener(csubl);
        sweep();

        // remove for /foo/bar
        Assertions.assertEquals(7, _bayeuxChannelListener._calls);
        Assertions.assertEquals("/foo/bar", _bayeuxChannelListener._channel);
        Assertions.assertEquals("removed", _bayeuxChannelListener._method);

        ServerChannelImpl fooBob = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bob").getReference();
        fooBob.subscribe(session0);
        ServerChannelImpl foo = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo").getReference();
        foo.subscribe(session0);
        foo.addListener(new ChannelSubscriptionListener());

        // config+add for /foo/bob and /foo
        Assertions.assertEquals(11, _bayeuxChannelListener._calls);

        foo.remove();

        // removed for /foo
        Assertions.assertEquals(12, _bayeuxChannelListener._calls);
        Assertions.assertEquals("/foo", _bayeuxChannelListener._channel);
        Assertions.assertEquals("removed", _bayeuxChannelListener._method);
        Assertions.assertNull(_bayeux.getChannel("/foo"));
        Assertions.assertEquals(0, foo.getSubscribers().size());
        Assertions.assertEquals(0, foo.getListeners().size());
        Assertions.assertNotNull(_bayeux.getChannel("/foo/bob"));
        Assertions.assertEquals(1, fooBob.getSubscribers().size());
        Assertions.assertNotNull(_bayeux.getChannel("/foo/*"));
    }

    @Test
    public void testUnSubscribeAll() {
        ServerChannelImpl channel = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        ServerSessionImpl session0 = newServerSession();

        channel.subscribe(session0);
        Assertions.assertEquals(1, channel.getSubscribers().size());
        Assertions.assertTrue(channel.getSubscribers().contains(session0));

        _bayeux.removeServerSession(session0, false);

        Assertions.assertEquals(0, channel.getSubscribers().size());
        Assertions.assertFalse(channel.getSubscribers().contains(session0));
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
        Assertions.assertEquals(1, session0.getQueue().size());
        Assertions.assertEquals(1, session1.getQueue().size());
        Assertions.assertEquals(1, session2.getQueue().size());

        foobob.publish(session0, _bayeux.newMessage(msg), Promise.noop());
        Assertions.assertEquals(1, session0.getQueue().size());
        Assertions.assertEquals(2, session1.getQueue().size());
        Assertions.assertEquals(2, session2.getQueue().size());

        wibble.publish(session0, _bayeux.newMessage(msg), Promise.noop());
        Assertions.assertEquals(1, session0.getQueue().size());
        Assertions.assertEquals(2, session1.getQueue().size());
        Assertions.assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setData("ignore");
        foobar.publish(session0, msg, Promise.noop());
        Assertions.assertEquals(1, session0.getQueue().size());
        Assertions.assertEquals(2, session1.getQueue().size());
        Assertions.assertEquals(3, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setChannel("/lazy");
        msg.setData("foostar");
        msg.setLazy(true);
        foobar.publish(session0, msg, Promise.noop());
        Assertions.assertEquals(2, session0.getQueue().size());
        Assertions.assertEquals(3, session1.getQueue().size());
        Assertions.assertEquals(4, session2.getQueue().size());

        msg = _bayeux.newMessage();
        msg.setChannel("/lazy");
        msg.setData("starstar");
        msg.setLazy(true);
        foobar.publish(session0, msg, Promise.noop());
        Assertions.assertEquals(3, session0.getQueue().size());
        Assertions.assertEquals(4, session1.getQueue().size());
        Assertions.assertEquals(5, session2.getQueue().size());

        Assertions.assertEquals("Hello World", session0.getQueue().poll().getData());
        Assertions.assertEquals("FooStar", session0.getQueue().poll().getData());
        Assertions.assertEquals("StarStar", session0.getQueue().poll().getData());
    }

    @Test
    public void testPublishFromSweptChannelSucceeds() throws Exception {
        _bayeux.start();

        ServerChannelImpl fooStarStar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/**").getReference();

        ServerSessionImpl session1 = newServerSession();
        fooStarStar.subscribe(session1);

        ServerChannel fooBar = _bayeux.createChannelIfAbsent("/foo/bar").getReference();

        sweep();

        Assertions.assertNull(_bayeux.getChannel(fooBar.getId()));

        ServerSessionImpl session0 = newServerSession();
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setData("test");
        fooBar.publish(session0, message, Promise.noop());

        Assertions.assertEquals(1, session1.getQueue().size());
    }

    @Test
    public void testPersistentChannelIsNotSwept() {
        String channelName = "/foo/bar";
        ServerChannel foobar = _bayeux.createChannelIfAbsent(channelName).getReference();
        foobar.setPersistent(true);

        sweep();
        Assertions.assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelWithSubscriberIsNotSwept() {
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        Assertions.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        // First sweep does not remove the channel yet
        _bayeux.sweep();
        Assertions.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Nor a second sweep
        _bayeux.sweep();
        Assertions.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));
        // Third sweep removes it
        _bayeux.sweep();
        Assertions.assertNull(_bayeux.getChannel("/foo/bar"));

        _bayeux.createChannelIfAbsent("/foo/bar/baz").getReference().remove();
        Assertions.assertNull(_bayeux.getChannel("/foo/bar/baz"));
        Assertions.assertNull(_bayeux.getChannel("/foo/bar"));
        Assertions.assertNull(_bayeux.getChannel("/foo"));

        foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar").getReference();
        Assertions.assertEquals(foobar, _bayeux.getChannel("/foo/bar"));

        ServerChannelImpl foobarbaz = (ServerChannelImpl)_bayeux.createChannelIfAbsent("/foo/bar/baz").getReference();
        ServerSessionImpl session0 = newServerSession();
        foobarbaz.subscribe(session0);
        _bayeux.createChannelIfAbsent("/foo").getReference().subscribe(session0);

        sweep();
        Assertions.assertNotNull(_bayeux.getChannel("/foo/bar/baz"));
        Assertions.assertNull(_bayeux.getChannel("/foo/bar"));
        Assertions.assertNotNull(_bayeux.getChannel("/foo"));

        foobarbaz.unsubscribe(session0);

        sweep();
        Assertions.assertNull(_bayeux.getChannel("/foo/bar/baz"));
        Assertions.assertNull(_bayeux.getChannel("/foo/bar"));
        Assertions.assertNotNull(_bayeux.getChannel("/foo"));

        _bayeux.getChannel("/foo").unsubscribe(session0);

        sweep();
        Assertions.assertNull(_bayeux.getChannel("/foo"));
    }

    @Test
    public void testChannelWithListenersIsNotSwept() {
        String channelName = "/test";
        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName).getReference();
        channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
                return true;
            }
        });

        sweep();

        Assertions.assertNotNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testChannelsWithAutorizersSweeping() {
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
        Assertions.assertNotNull(_bayeux.getChannel(channelName1));
        Assertions.assertNotNull(_bayeux.getChannel(wildName1));
        Assertions.assertNotNull(_bayeux.getChannel(wildName2));

        // Remove the authorizer from a wild parent must sweep the wild parent
        _bayeux.getChannel(wildName2).removeAuthorizer(GrantAuthorizer.GRANT_ALL);

        sweep();

        Assertions.assertNotNull(_bayeux.getChannel(channelName1));
        Assertions.assertNotNull(_bayeux.getChannel(wildName1));
        Assertions.assertNull(_bayeux.getChannel(wildName2));

        // Remove the listener from a channel must not sweep the wild parent with authorizer
        // since other channels may be added later that will match the wild channel
        _bayeux.getChannel(channelName1).removeListener(listener);

        sweep();

        Assertions.assertNull(_bayeux.getChannel(channelName1));
        Assertions.assertNotNull(_bayeux.getChannel(wildName1));
        Assertions.assertNull(_bayeux.getChannel(wildName2));
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

        ServerChannel.ServerChannelListener listener = new L();
        String channelName = "/weak";
        _bayeux.createChannelIfAbsent(channelName, channel -> {
            channel.addListener(listener);
            channel.addListener(new W());
        });

        sweep();

        // Non-weak listener present, must not be swept
        Assertions.assertNotNull(_bayeux.getChannel(channelName));

        _bayeux.getChannel(channelName).removeListener(listener);
        sweep();

        // Only weak listeners present, must be swept
        Assertions.assertNull(_bayeux.getChannel(channelName));
    }

    @Test
    public void testLazyTimeout() {
        String channelName = "/testLazy";
        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer.Persistent()).getReference();
        Assertions.assertFalse(channel.isLazy());

        int lazyTimeout = 1000;
        channel.setLazyTimeout(lazyTimeout);
        Assertions.assertTrue(channel.isLazy());

        channel.setLazy(true);
        Assertions.assertEquals(lazyTimeout, channel.getLazyTimeout());

        channel.setLazy(false);
        Assertions.assertFalse(channel.isLazy());
        Assertions.assertEquals(-1, channel.getLazyTimeout());

        channel.setLazy(true);
        Assertions.assertTrue(channel.isLazy());
        Assertions.assertEquals(-1, channel.getLazyTimeout());
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
