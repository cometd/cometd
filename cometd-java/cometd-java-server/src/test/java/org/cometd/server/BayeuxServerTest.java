/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxServerTest {
    private final Queue<Object> _events = new ConcurrentLinkedQueue<>();
    private final BayeuxServerImpl _bayeux = new BayeuxServerImpl();

    private ServerSessionImpl newServerSession() {
        ServerSessionImpl session = _bayeux.newServerSession();
        _bayeux.addServerSession(session, _bayeux.newMessage());
        session.handshake();
        session.connected();
        return session;
    }

    @Before
    public void init() throws Exception {
        _bayeux.start();
    }

    @After
    public void destroy() throws Exception {
        _bayeux.stop();
        _events.clear();
    }

    @Test
    public void testListeners() throws Exception {
        _bayeux.addListener(new SubListener());
        _bayeux.addListener(new SessListener());
        _bayeux.addListener(new CListener());

        String channelName = "/foo/bar";
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.createChannelIfAbsent(channelName).getReference();
        channelName = "/foo/*";
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.createChannelIfAbsent(channelName).getReference();
        channelName = "/**";
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.createChannelIfAbsent(channelName).getReference();
        channelName = "/foo/bob";
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.createChannelIfAbsent(channelName).getReference();
        channelName = "/wibble";
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.createChannelIfAbsent(channelName).getReference();

        Assert.assertEquals("channelAdded", _events.poll());
        Assert.assertEquals(foobar, _events.poll());
        Assert.assertEquals("channelAdded", _events.poll());
        Assert.assertEquals(foostar, _events.poll());
        Assert.assertEquals("channelAdded", _events.poll());
        Assert.assertEquals(starstar, _events.poll());
        Assert.assertEquals("channelAdded", _events.poll());
        Assert.assertEquals(foobob, _events.poll());
        Assert.assertEquals("channelAdded", _events.poll());
        Assert.assertEquals(wibble, _events.poll());

        wibble.remove();
        Assert.assertEquals("channelRemoved", _events.poll());
        Assert.assertEquals(wibble.getId(), _events.poll());

        ServerSessionImpl session0 = newServerSession();
        ServerSessionImpl session1 = newServerSession();
        ServerSessionImpl session2 = newServerSession();

        Assert.assertEquals("sessionAdded", _events.poll());
        Assert.assertEquals(session0, _events.poll());
        Assert.assertEquals("sessionAdded", _events.poll());
        Assert.assertEquals(session1, _events.poll());
        Assert.assertEquals("sessionAdded", _events.poll());
        Assert.assertEquals(session2, _events.poll());

        foobar.subscribe(session0);
        foobar.unsubscribe(session0);

        Assert.assertEquals("subscribed", _events.poll());
        Assert.assertEquals(session0, _events.poll());
        Assert.assertEquals(foobar, _events.poll());
        Assert.assertEquals("unsubscribed", _events.poll());
        Assert.assertEquals(session0, _events.poll());
        Assert.assertEquals(foobar, _events.poll());
    }

    @Test
    public void testSessionAttributes() throws Exception {
        LocalSession local = _bayeux.newLocalSession("s0");
        local.handshake();
        ServerSession session = local.getServerSession();

        local.setAttribute("foo", "bar");
        Assert.assertEquals("bar", local.getAttribute("foo"));
        Assert.assertEquals(null, session.getAttribute("foo"));

        session.setAttribute("bar", "foo");
        Assert.assertEquals(null, local.getAttribute("bar"));
        Assert.assertEquals("foo", session.getAttribute("bar"));

        Assert.assertTrue(local.getAttributeNames().contains("foo"));
        Assert.assertFalse(local.getAttributeNames().contains("bar"));
        Assert.assertFalse(session.getAttributeNames().contains("foo"));
        Assert.assertTrue(session.getAttributeNames().contains("bar"));

        Assert.assertEquals("bar", local.removeAttribute("foo"));
        Assert.assertEquals(null, local.removeAttribute("foo"));
        Assert.assertEquals("foo", session.removeAttribute("bar"));
        Assert.assertEquals(null, local.removeAttribute("bar"));
    }

    @Test
    public void testLocalSessions() throws Exception {
        LocalSession session0 = _bayeux.newLocalSession("s0");
        Assert.assertEquals("L:s0_<disconnected>", session0.toString());
        session0.handshake();
        Assert.assertNotEquals("L:s0_", session0.toString());
        Assert.assertTrue(session0.toString().startsWith("L:s0_"));

        final LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();
        final LocalSession session2 = _bayeux.newLocalSession("s2");
        session2.handshake();

        final Queue<String> events = new ConcurrentLinkedQueue<>();

        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                events.add(channel.getSession().getId());
                events.add(message.getData().toString());
            }
        };

        session0.getChannel("/foo/bar").subscribe(listener);
        session0.getChannel("/foo/bar").subscribe(listener);
        session1.getChannel("/foo/bar").subscribe(listener);
        session2.getChannel("/foo/bar").subscribe(listener);

        Assert.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assert.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());
        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assert.assertEquals(2, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        ClientSessionChannel foobar0 = session0.getChannel("/foo/bar");
        foobar0.subscribe(listener);
        foobar0.subscribe(listener);

        ClientSessionChannel foostar0 = session0.getChannel("/foo/*");
        foostar0.subscribe(listener);

        Assert.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());
        Assert.assertEquals(session0, foobar0.getSession());
        Assert.assertEquals("/foo/bar", foobar0.getId());
        Assert.assertEquals(false, foobar0.isDeepWild());
        Assert.assertEquals(false, foobar0.isWild());
        Assert.assertEquals(false, foobar0.isMeta());
        Assert.assertEquals(false, foobar0.isService());

        foobar0.publish("hello");

        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("hello", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("hello", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("hello", events.poll());
        Assert.assertEquals(session1.getId(), events.poll());
        Assert.assertEquals("hello", events.poll());
        Assert.assertEquals(session2.getId(), events.poll());
        Assert.assertEquals("hello", events.poll());
        foostar0.unsubscribe(listener);

        session1.batch(new Runnable() {
            @Override
            public void run() {
                ClientSessionChannel foobar1 = session1.getChannel("/foo/bar");
                foobar1.publish("part1");
                Assert.assertEquals(null, events.poll());
                foobar1.publish("part2");
            }
        });

        Assert.assertEquals(session1.getId(), events.poll());
        Assert.assertEquals("part1", events.poll());
        Assert.assertEquals(session2.getId(), events.poll());
        Assert.assertEquals("part1", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("part1", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("part1", events.poll());
        Assert.assertEquals(session1.getId(), events.poll());
        Assert.assertEquals("part2", events.poll());
        Assert.assertEquals(session2.getId(), events.poll());
        Assert.assertEquals("part2", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("part2", events.poll());
        Assert.assertEquals(session0.getId(), events.poll());
        Assert.assertEquals("part2", events.poll());

        foobar0.unsubscribe();
        Assert.assertEquals(2, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        Assert.assertTrue(session0.isConnected());
        Assert.assertTrue(session1.isConnected());
        Assert.assertTrue(session2.isConnected());
        ServerSession ss0 = session0.getServerSession();
        ServerSession ss1 = session1.getServerSession();
        ServerSession ss2 = session2.getServerSession();
        Assert.assertTrue(ss0.isConnected());
        Assert.assertTrue(ss1.isConnected());
        Assert.assertTrue(ss2.isConnected());

        session0.disconnect();
        Assert.assertFalse(session0.isConnected());
        Assert.assertFalse(ss0.isConnected());

        session1.getServerSession().disconnect();
        Assert.assertFalse(session1.isConnected());
        Assert.assertFalse(ss1.isConnected());

        session2.getServerSession().disconnect();
        Assert.assertFalse(session2.isConnected());
        Assert.assertFalse(ss2.isConnected());
    }

    class CListener implements BayeuxServer.ChannelListener {
        @Override
        public void configureChannel(ConfigurableServerChannel channel) {
        }

        @Override
        public void channelAdded(ServerChannel channel) {
            _events.add("channelAdded");
            _events.add(channel);
        }

        @Override
        public void channelRemoved(String channelId) {
            _events.add("channelRemoved");
            _events.add(channelId);
        }

    }

    class SessListener implements BayeuxServer.SessionListener {
        @Override
        public void sessionAdded(ServerSession session, ServerMessage message) {
            _events.add("sessionAdded");
            _events.add(session);
        }

        @Override
        public void sessionRemoved(ServerSession session, boolean timedout) {
            _events.add("sessionRemoved");
            _events.add(session);
            _events.add(timedout);
        }
    }

    class SubListener implements BayeuxServer.SubscriptionListener {
        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _events.add("subscribed");
            _events.add(session);
            _events.add(channel);
        }

        @Override
        public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            _events.add("unsubscribed");
            _events.add(session);
            _events.add(channel);
        }

    }
}
