package org.cometd.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import junit.framework.Assert;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Before;
import org.junit.Test;



public class BayeuxServerTest extends Assert
{
    Queue<Object> _events = new ConcurrentLinkedQueue<Object>();
    BayeuxServerImpl _bayeux = new BayeuxServerImpl();
    
    @Before
    public void setup()
    {
    }
    
    @Test
    public void testListeners() throws Exception
    {
        _bayeux.addListener(new SubListener());
        _bayeux.addListener(new SessListener());
        _bayeux.addListener(new CListener());
        
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar",true);
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.getChannel("/foo/*",true);
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.getChannel("/**",true);
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.getChannel("/foo/bob",true);
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.getChannel("/wibble",true);
        
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
        
        ServerSessionImpl session0 = _bayeux.newServerSession();
        ServerSessionImpl session1 = _bayeux.newServerSession();
        ServerSessionImpl session2 = _bayeux.newServerSession();

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
        local.handshake(true);
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
        session0.handshake(true);
        assertTrue(session0.toString().indexOf("s0_")>=0);
        
        LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake(true);
        LocalSession session2 = _bayeux.newLocalSession("s2");
        session2.handshake(true);
        
        final Queue<String> events = new ConcurrentLinkedQueue<String>();
        
        ClientSession.MessageListener listener = new ClientSession.MessageListener()
        {
            public void onMessage(ClientSession session, Message message)
            {
                events.add(session.getId());
                events.add(message.getData().toString());
            }
        };
        
        session0.getChannel("/foo/bar").subscribe(listener);
        session0.getChannel("/foo/bar").subscribe(listener);
        session1.getChannel("/foo/bar").subscribe(listener);
        session2.getChannel("/foo/bar").subscribe(listener);
        
        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        
        session0.getChannel("/foo/bar").unsubscribe(listener);
        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        session0.getChannel("/foo/bar").unsubscribe(listener);
        assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        
        SessionChannel foobar0=session0.getChannel("/foo/bar");
        foobar0.subscribe(listener);
        foobar0.subscribe(listener);
        assertEquals(3,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        assertEquals(session0,foobar0.getSession());
        assertEquals("/foo/bar",foobar0.getId());
        assertEquals(false,foobar0.isDeepWild());
        assertEquals(false,foobar0.isWild());
        assertEquals(false,foobar0.isMeta());
        assertEquals(false,foobar0.isService());      
        
        foobar0.publish("hello");
        
        assertEquals(session1.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session2.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("hello",events.poll());
        assertEquals(session0.getId(),events.poll());
        assertEquals("hello",events.poll());
        
        foobar0.unsubscribe();
        assertEquals(2,_bayeux.getChannel("/foo/bar").getSubscribers().size());
        
    }
    
    class CListener implements BayeuxServer.ChannelListener
    {

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
