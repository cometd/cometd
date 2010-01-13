package org.cometd.server;

import junit.framework.Assert;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.common.ChannelId;
import org.junit.Before;
import org.junit.Test;



public class ServerChannelTest extends Assert
{
    ChanL _chanl = new ChanL();
    SSubL _subl = new SSubL();
    BayeuxServerImpl _bayeux = new BayeuxServerImpl();
    
    @Before
    public void setup()
    {
        _bayeux.addListener(_chanl);
        _bayeux.addListener(_subl);
    }
    
    @Test
    public void testRoot() throws Exception
    {
        final ServerChannelImpl root = _bayeux.root();
        
        assertEquals(0,root.getChannelId().depth());
        assertEquals("/",root.getChannelId().toString());
        assertEquals(null,root.getChild(new ChannelId("/foo"),false));
        
        assertEquals(0,root.getSubscribers().size());
    }

    @Test
    public void testChannelCreate() throws Exception
    {
        assertTrue(_bayeux.getChannel("/foo")==null);
        assertTrue(_bayeux.getChannel("/foo/bar")==null);
        _bayeux.getChannel("/foo/bar",true);
        assertTrue(_bayeux.getChannel("/foo")!=null);
        assertTrue(_bayeux.getChannel("/foo/bar")!=null);
        assertEquals(2,_chanl._calls);
        assertEquals("added",_chanl._method);
        assertEquals("/foo/bar",_chanl._channel);
        _bayeux.getChannel("/foo/bob",true);
        assertTrue(_bayeux.getChannel("/foo/bob")!=null);
        assertEquals(3,_chanl._calls);
        assertEquals("added",_chanl._method);
        assertEquals("/foo/bob",_chanl._channel);
    }
    
    @Test
    public void testSubscribe() throws Exception
    {
        ServerChannelImpl channel = (ServerChannelImpl)_bayeux.getChannel("/foo/bar",true);
        SubListener csubl = new SubListener();
        channel.addListener(csubl);
        ServerSessionImpl session0 = _bayeux.newServerSession();
        
        channel.subscribe(session0);
        assertEquals(1,channel.getSubscribers().size());
        assertTrue(channel.getSubscribers().contains(session0));
        assertEquals("subscribed",_subl._method);
        assertEquals(channel,_subl._channel);
        assertEquals(session0,_subl._session);
        assertEquals("subscribed",csubl._method);
        assertEquals(channel,csubl._channel);
        assertEquals(session0,csubl._session);
        assertEquals(2,_chanl._calls);
        
        ServerSessionImpl session1 = _bayeux.newServerSession();
        ((ServerChannelImpl)_bayeux.getChannel("/foo/*",true)).subscribe(session1);
        assertEquals("subscribed",_subl._method);
        assertEquals("/foo/*",_subl._channel.getId());
        assertEquals(session1,_subl._session);
        assertEquals(3,_chanl._calls);
        
        ServerSessionImpl session2 = _bayeux.newServerSession();
        ((ServerChannelImpl)_bayeux.getChannel("/**",true)).subscribe(session2);
        assertEquals("subscribed",_subl._method);
        assertEquals("/**",_subl._channel.getId());
        assertEquals(session2,_subl._session);
        assertEquals(4,_chanl._calls);
        
        channel.unsubscribe(session0);
        assertEquals(0,channel.getSubscribers().size());
        assertFalse(channel.getSubscribers().contains(session0));
        assertEquals("unsubscribed",_subl._method);
        assertEquals(channel,_subl._channel);
        assertEquals(session0,_subl._session);
        assertEquals("unsubscribed",csubl._method);
        assertEquals(channel,csubl._channel);
        assertEquals(session0,csubl._session);
        
        assertEquals(5,_chanl._calls);
        assertEquals("/foo/bar",_chanl._channel);
        assertEquals("removed",_chanl._method);


        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.getChannel("/foo/bob",true);
        foobob.subscribe(session0);
        ServerChannelImpl foo = (ServerChannelImpl)_bayeux.getChannel("/foo");
        foo.subscribe(session0);
        foo.addListener(new SubListener());

        assertEquals(6,_chanl._calls);
        
        foo.remove();

        assertEquals(9,_chanl._calls);
        assertEquals("/foo",_chanl._channel);
        assertEquals("removed",_chanl._method);
        assertEquals(0,foo.getSubscribers().size());
        assertEquals(0,foobob.getSubscribers().size());
           
    }

    @Test
    public void testPublish() throws Exception
    {
        ServerChannelImpl foobar = (ServerChannelImpl)_bayeux.getChannel("/foo/bar",true);
        ServerChannelImpl foostar = (ServerChannelImpl)_bayeux.getChannel("/foo/*",true);
        ServerChannelImpl starstar = (ServerChannelImpl)_bayeux.getChannel("/**",true);
        ServerChannelImpl foobob = (ServerChannelImpl)_bayeux.getChannel("/foo/bob",true);
        ServerChannelImpl wibble = (ServerChannelImpl)_bayeux.getChannel("/wibble",true);
        
        foobar.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                return  !"ignore".equals(message.getData());
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
                return  true;
            }
        });
        
        ServerSessionImpl session0 = _bayeux.newServerSession();
        _bayeux.addServerSession(session0);
        
        // this is a private API - not a normal subscribe!!
        foobar.subscribe(session0);
      
        ServerSessionImpl session1 = _bayeux.newServerSession();
        _bayeux.addServerSession(session1);
        foostar.subscribe(session1);
        ServerSessionImpl session2 = _bayeux.newServerSession();
        _bayeux.addServerSession(session2);
        starstar.subscribe(session2);
        
        ServerMessage.Mutable msg = _bayeux.newMessage();
        msg.setData("Hello World");
        
        foobar.publish(session0,msg);
        assertEquals(1,session0.getQueue().size());
        assertEquals(1,session1.getQueue().size());
        assertEquals(1,session2.getQueue().size());
        
        foobob.publish(session0,msg);
        assertEquals(1,session0.getQueue().size());
        assertEquals(2,session1.getQueue().size());
        assertEquals(2,session2.getQueue().size());
        
        wibble.publish(session0,msg);
        assertEquals(1,session0.getQueue().size());
        assertEquals(2,session1.getQueue().size());
        assertEquals(3,session2.getQueue().size());   
        
        msg = _bayeux.newMessage();
        msg.setData("ignore");
        foobar.publish(session0,msg);
        assertEquals(1,session0.getQueue().size());
        assertEquals(2,session1.getQueue().size());
        assertEquals(3,session2.getQueue().size());  
        
        msg = _bayeux.newMessage();
        msg.setData("foostar");
        msg.setLazy(true);
        foobar.publish(session0,msg);
        assertEquals(2,session0.getQueue().size());
        assertEquals(3,session1.getQueue().size());
        assertEquals(4,session2.getQueue().size());  
        
        msg = _bayeux.newMessage();
        msg.setData("starstar");
        msg.setLazy(true);
        foobar.publish(session0,msg);
        assertEquals(3,session0.getQueue().size());
        assertEquals(4,session1.getQueue().size());
        assertEquals(5,session2.getQueue().size());  
        
        assertEquals("Hello World",session0.getQueue().poll().getData());
        assertEquals("FooStar",session0.getQueue().poll().getData());
        assertEquals("StarStar",session0.getQueue().poll().getData());
        
  
    }
    
    static class SSubL implements BayeuxServer.SubscriptionListener
    {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;
        
        public void reset()
        {
            _method=null;
            _session=null;
            _channel=null;
        }
        
        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _method="subscribed";
            _session=session;
            _channel=channel;
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _method="unsubscribed";
            _session=session;
            _channel=channel;
        }
        
    }
    
    static class SubListener implements ServerChannel.SubscriptionListener
    {
        public String _method;
        public ServerSession _session;
        public ServerChannel _channel;
        
        public void reset()
        {
            _method=null;
            _session=null;
            _channel=null;
        }
        
        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _method="subscribed";
            _session=session;
            _channel=channel;
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _method="unsubscribed";
            _session=session;
            _channel=channel;
        }
        
    }
    
    static class ChanL implements BayeuxServer.ChannelListener
    {
        public int _calls;
        public String _method;
        public String _channel;
        
        public void reset()
        {
            _calls=0;
            _method=null;
            _channel=null;
        }
        

        public void channelAdded(ServerChannel channel)
        {
            _calls++;
            _method="added";
            _channel=channel.getId();
        }

        public void channelRemoved(String channelId)
        {
            _calls++;
            _method="removed";
            _channel=channelId;
        }
        
    }
    
}
