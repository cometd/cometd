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

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BayeuxServerTest {
    private final Queue<Object> _events = new ConcurrentLinkedQueue<>();
    private final BayeuxServerImpl _bayeux = new BayeuxServerImpl();

    private ServerSessionImpl newServerSession() {
        ServerSessionImpl session = _bayeux.newServerSession();
        _bayeux.addServerSession(session, _bayeux.newMessage());
        session.handshake(null);
        session.connected();
        return session;
    }

    @BeforeEach
    public void init() throws Exception {
        _bayeux.start();
    }

    @AfterEach
    public void destroy() throws Exception {
        _bayeux.stop();
        _events.clear();
    }

    @Test
    public void testDumpDuration() {
        _bayeux.setDetailedDump(true);
        for (int i = 0; i < 10_000; i++) {
            newServerSession();
        }

        String prefix = "total dump duration=";
        String suffix = "ms";
        String dump = _bayeux.dump();
        int idx = dump.indexOf(prefix);
        Assertions.assertNotEquals(-1, idx);
        String ms = dump.substring(idx + prefix.length(), dump.indexOf(suffix, idx));
        Assertions.assertTrue(Integer.parseInt(ms) > 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 10, 16, 100, 10_000})
    public void testAsyncSweep(int count) throws Exception {
        Set<String> sessionIds = ConcurrentHashMap.newKeySet();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        Set<String> removedSessionIds = ConcurrentHashMap.newKeySet();
        _bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message)
            {
                sessionIds.add(session.getId());
            }
            @Override
            public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout)
            {
                threads.add(Thread.currentThread());
                removedSessionIds.add(session.getId());
            }
        });

        for (int i = 0; i < count; i++) {
            newServerSession().scheduleExpiration(0, 0, 0);
        }

        _bayeux.asyncSweep().get();

        assertEquals(count, removedSessionIds.size());
        for (String sessionId : sessionIds) {
            assertTrue(removedSessionIds.contains(sessionId));
        }

        if (count >= 100)
            assertTrue(threads.size() >= 2);
    }

    @Test
    public void testListeners() {
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

        Assertions.assertEquals("channelAdded", _events.poll());
        Assertions.assertEquals(foobar, _events.poll());
        Assertions.assertEquals("channelAdded", _events.poll());
        Assertions.assertEquals(foostar, _events.poll());
        Assertions.assertEquals("channelAdded", _events.poll());
        Assertions.assertEquals(starstar, _events.poll());
        Assertions.assertEquals("channelAdded", _events.poll());
        Assertions.assertEquals(foobob, _events.poll());
        Assertions.assertEquals("channelAdded", _events.poll());
        Assertions.assertEquals(wibble, _events.poll());

        wibble.remove();
        Assertions.assertEquals("channelRemoved", _events.poll());
        Assertions.assertEquals(wibble.getId(), _events.poll());

        ServerSessionImpl session0 = newServerSession();
        ServerSessionImpl session1 = newServerSession();
        ServerSessionImpl session2 = newServerSession();

        Assertions.assertEquals("sessionAdded", _events.poll());
        Assertions.assertEquals(session0, _events.poll());
        Assertions.assertEquals("sessionAdded", _events.poll());
        Assertions.assertEquals(session1, _events.poll());
        Assertions.assertEquals("sessionAdded", _events.poll());
        Assertions.assertEquals(session2, _events.poll());

        foobar.subscribe(session0);
        foobar.unsubscribe(session0);

        Assertions.assertEquals("subscribed", _events.poll());
        Assertions.assertEquals(session0, _events.poll());
        Assertions.assertEquals(foobar, _events.poll());
        Assertions.assertEquals("unsubscribed", _events.poll());
        Assertions.assertEquals(session0, _events.poll());
        Assertions.assertEquals(foobar, _events.poll());
    }

    @Test
    public void testSessionAttributes() {
        LocalSession local = _bayeux.newLocalSession("s0");
        local.handshake();
        ServerSession session = local.getServerSession();

        local.setAttribute("foo", "bar");
        Assertions.assertEquals("bar", local.getAttribute("foo"));
        Assertions.assertNull(session.getAttribute("foo"));

        session.setAttribute("bar", "foo");
        Assertions.assertNull(local.getAttribute("bar"));
        Assertions.assertEquals("foo", session.getAttribute("bar"));

        Assertions.assertTrue(local.getAttributeNames().contains("foo"));
        Assertions.assertFalse(local.getAttributeNames().contains("bar"));
        Assertions.assertFalse(session.getAttributeNames().contains("foo"));
        Assertions.assertTrue(session.getAttributeNames().contains("bar"));

        Assertions.assertEquals("bar", local.removeAttribute("foo"));
        Assertions.assertNull(local.removeAttribute("foo"));
        Assertions.assertEquals("foo", session.removeAttribute("bar"));
        Assertions.assertNull(local.removeAttribute("bar"));
    }

    @Test
    public void testLocalSessions() {
        LocalSession session0 = _bayeux.newLocalSession("s0");
        Assertions.assertEquals("L:s0_<disconnected>", session0.toString());
        session0.handshake();
        Assertions.assertNotEquals("L:s0_", session0.toString());
        Assertions.assertTrue(session0.toString().startsWith("L:s0_"));

        LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();
        LocalSession session2 = _bayeux.newLocalSession("s2");
        session2.handshake();

        Queue<String> events = new ConcurrentLinkedQueue<>();

        ClientSessionChannel.MessageListener listener = (channel, message) -> {
            events.add(channel.getSession().getId());
            events.add(message.getData().toString());
        };

        session0.getChannel("/foo/bar").subscribe(listener);
        session0.getChannel("/foo/bar").subscribe(listener);
        session1.getChannel("/foo/bar").subscribe(listener);
        session2.getChannel("/foo/bar").subscribe(listener);

        Assertions.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assertions.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());
        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assertions.assertEquals(2, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        ClientSessionChannel foobar0 = session0.getChannel("/foo/bar");
        foobar0.subscribe(listener);
        foobar0.subscribe(listener);

        ClientSessionChannel foostar0 = session0.getChannel("/foo/*");
        foostar0.subscribe(listener);

        Assertions.assertEquals(3, _bayeux.getChannel("/foo/bar").getSubscribers().size());
        Assertions.assertEquals(session0, foobar0.getSession());
        Assertions.assertEquals("/foo/bar", foobar0.getId());
        Assertions.assertFalse(foobar0.isDeepWild());
        Assertions.assertFalse(foobar0.isWild());
        Assertions.assertFalse(foobar0.isMeta());
        Assertions.assertFalse(foobar0.isService());

        foobar0.publish("hello");

        List<String> sessionIds = new ArrayList<>();
        for (int i = 0; i < 5; ++i) {
            sessionIds.add(events.poll());
            Assertions.assertEquals("hello", events.poll());
        }
        Assertions.assertEquals(3, sessionIds.stream().filter(id -> id.equals(session0.getId())).count());
        Assertions.assertEquals(1, sessionIds.stream().filter(id -> id.equals(session1.getId())).count());
        Assertions.assertEquals(1, sessionIds.stream().filter(id -> id.equals(session2.getId())).count());
        foostar0.unsubscribe(listener);

        session1.batch(() -> {
            ClientSessionChannel foobar1 = session1.getChannel("/foo/bar");
            foobar1.publish("part1");
            Assertions.assertNull(events.poll());
            foobar1.publish("part2");
        });

        sessionIds.clear();
        List<String> data = new ArrayList<>();
        for (int i = 0; i < 8; ++i) {
            sessionIds.add(events.poll());
            data.add(events.poll());
        }
        Assertions.assertEquals(4, sessionIds.stream().filter(id -> id.equals(session0.getId())).count());
        Assertions.assertEquals(2, sessionIds.stream().filter(id -> id.equals(session1.getId())).count());
        Assertions.assertEquals(2, sessionIds.stream().filter(id -> id.equals(session2.getId())).count());
        Assertions.assertEquals(4, data.stream().filter(id -> id.equals("part1")).count());
        Assertions.assertEquals(4, data.stream().filter(id -> id.equals("part2")).count());

        foobar0.unsubscribe();
        Assertions.assertEquals(2, _bayeux.getChannel("/foo/bar").getSubscribers().size());

        Assertions.assertTrue(session0.isConnected());
        Assertions.assertTrue(session1.isConnected());
        Assertions.assertTrue(session2.isConnected());
        ServerSession ss0 = session0.getServerSession();
        ServerSession ss1 = session1.getServerSession();
        ServerSession ss2 = session2.getServerSession();
        Assertions.assertTrue(ss0.isConnected());
        Assertions.assertTrue(ss1.isConnected());
        Assertions.assertTrue(ss2.isConnected());

        session0.disconnect();
        Assertions.assertFalse(session0.isConnected());
        Assertions.assertFalse(ss0.isConnected());

        session1.getServerSession().disconnect();
        Assertions.assertFalse(session1.isConnected());
        Assertions.assertFalse(ss1.isConnected());

        session2.getServerSession().disconnect();
        Assertions.assertFalse(session2.isConnected());
        Assertions.assertFalse(ss2.isConnected());
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
        public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
            _events.add("sessionRemoved");
            _events.add(session);
            _events.add(timeout);
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
