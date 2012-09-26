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

package org.cometd.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxServerTest
{
    private final Queue<Object> _events = new ConcurrentLinkedQueue<>();
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

        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(_bayeux.getChannel("/foo"),_events.poll());
        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(foobar,_events.poll());
        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(foostar,_events.poll());
        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(starstar,_events.poll());
        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(foobob,_events.poll());
        Assert.assertEquals("channelAdded",_events.poll());
        Assert.assertEquals(wibble,_events.poll());

        wibble.remove();
        Assert.assertEquals("channelRemoved",_events.poll());
        Assert.assertEquals(wibble.getId(),_events.poll());

        ServerSessionImpl session0 = newServerSession();
        ServerSessionImpl session1 = newServerSession();
        ServerSessionImpl session2 = newServerSession();

        Assert.assertEquals("sessionAdded",_events.poll());
        Assert.assertEquals(session0,_events.poll());
        Assert.assertEquals("sessionAdded",_events.poll());
        Assert.assertEquals(session1,_events.poll());
        Assert.assertEquals("sessionAdded",_events.poll());
        Assert.assertEquals(session2,_events.poll());

        foobar.subscribe(session0);
        foobar.unsubscribe(session0);

        Assert.assertEquals("subscribed",_events.poll());
        Assert.assertEquals(session0,_events.poll());
        Assert.assertEquals(foobar,_events.poll());
        Assert.assertEquals("unsubscribed",_events.poll());
        Assert.assertEquals(session0,_events.poll());
        Assert.assertEquals(foobar,_events.poll());
    }

    @Test
    public void testSessionAttributes() throws Exception
    {
        LocalSession local = _bayeux.newLocalSession("s0");
        local.handshake();
        ServerSession session = local.getServerSession();

        local.setAttribute("foo","bar");
        Assert.assertEquals("bar",local.getAttribute("foo"));
        Assert.assertEquals(null,session.getAttribute("foo"));

        session.setAttribute("bar","foo");
        Assert.assertEquals(null,local.getAttribute("bar"));
        Assert.assertEquals("foo",session.getAttribute("bar"));

        Assert.assertTrue(local.getAttributeNames().contains("foo"));
        Assert.assertFalse(local.getAttributeNames().contains("bar"));
        Assert.assertFalse(session.getAttributeNames().contains("foo"));
        Assert.assertTrue(session.getAttributeNames().contains("bar"));

        Assert.assertEquals("bar",local.removeAttribute("foo"));
        Assert.assertEquals(null,local.removeAttribute("foo"));
        Assert.assertEquals("foo",session.removeAttribute("bar"));
        Assert.assertEquals(null,local.removeAttribute("bar"));
    }

    @Test
    public void testLocalSessions() throws Exception
    {
        LocalSession session0 = _bayeux.newLocalSession("s0");
        Assert.assertTrue(session0.toString().contains("s0?"));
        session0.handshake();
        Assert.assertTrue(session0.toString().contains("s0_"));

        final LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();
        final LocalSession session2 = _bayeux.newLocalSession("s2");
        session2.handshake();

        final Queue<String> events = new ConcurrentLinkedQueue<>();

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

        Assert.assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());

        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assert.assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        session0.getChannel("/foo/bar").unsubscribe(listener);
        Assert.assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());

        ClientSessionChannel foobar0=session0.getChannel("/foo/bar");
        foobar0.subscribe(listener);
        foobar0.subscribe(listener);

        ClientSessionChannel foostar0=session0.getChannel("/foo/*");
        foostar0.subscribe(listener);

        Assert.assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        Assert.assertEquals(session0,foobar0.getSession());
        Assert.assertEquals("/foo/bar",foobar0.getId());
        Assert.assertEquals(false,foobar0.isDeepWild());
        Assert.assertEquals(false,foobar0.isWild());
        Assert.assertEquals(false,foobar0.isMeta());
        Assert.assertEquals(false,foobar0.isService());

        foobar0.publish("hello");

        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("hello",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("hello",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("hello",events.poll());
        Assert.assertEquals(session1.getId(),events.poll());
        Assert.assertEquals("hello",events.poll());
        Assert.assertEquals(session2.getId(),events.poll());
        Assert.assertEquals("hello",events.poll());
        foostar0.unsubscribe(listener);

        session1.batch(new Runnable()
        {
            public void run()
            {
                ClientSessionChannel foobar1=session1.getChannel("/foo/bar");
                foobar1.publish("part1");
                Assert.assertEquals(null,events.poll());
                foobar1.publish("part2");
            }
        });

        Assert.assertEquals(session1.getId(),events.poll());
        Assert.assertEquals("part1",events.poll());
        Assert.assertEquals(session2.getId(),events.poll());
        Assert.assertEquals("part1",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("part1",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("part1",events.poll());
        Assert.assertEquals(session1.getId(),events.poll());
        Assert.assertEquals("part2",events.poll());
        Assert.assertEquals(session2.getId(),events.poll());
        Assert.assertEquals("part2",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("part2",events.poll());
        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("part2",events.poll());

        foobar0.unsubscribe();
        Assert.assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());

        Assert.assertTrue(session0.isConnected());
        Assert.assertTrue(session1.isConnected());
        Assert.assertTrue(session2.isConnected());
        ServerSession ss0=session0.getServerSession();
        ServerSession ss1=session1.getServerSession();
        ServerSession ss2=session2.getServerSession();
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

    @Test
    public void testExtensions() throws Exception
    {
        final Queue<String> events = new ConcurrentLinkedQueue<>();
        _bayeux.addExtension(new BayeuxServer.Extension.Adapter()
        {
            @Override
            public boolean send(ServerSession from, ServerSession to, Mutable message)
            {
                if ("three".equals(message.getData()))
                    message.setData("four");
                return !"ignoreSend".equals(message.getData());
            }

            @Override
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

        session0.addExtension(new ClientSession.Extension.Adapter()
        {
            @Override
            public boolean send(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                if ("zero".equals(message.getData()))
                    message.setData("one");
                return true;
            }

            @Override
            public boolean rcv(ClientSession session, org.cometd.bayeux.Message.Mutable message)
            {
                if ("five".equals(message.getData()))
                    message.setData("six");
                return true;
            }
        });

        session0.getServerSession().addExtension(new ServerSession.Extension.Adapter()
        {
            @Override
            public boolean rcv(ServerSession from, Mutable message)
            {
                if ("two".equals(message.getData()))
                    message.setData("three");
                return true;
            }

            @Override
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

        Assert.assertEquals(session0.getId(),events.poll());
        Assert.assertEquals("six",events.poll());

        /*
        Assert.assertEquals(session1.getId(),events.poll());
        Assert.assertEquals("four",events.poll());
        Assert.assertEquals(null,events.poll());
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
