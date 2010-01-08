package org.cometd.server;

import junit.framework.Assert;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
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
        CSubL csubl = new CSubL();
        channel.addListener(csubl);
        ServerSessionImpl session0 = new ServerSessionImpl(_bayeux);
        
        channel.subscribe(session0);
        assertEquals(1,channel.getSubscribers().size());
        assertTrue(channel.getSubscribers().contains(session0));
        assertEquals("subscribed",_subl._method);
        assertEquals(channel,_subl._channel);
        assertEquals(session0,_subl._session);
        assertEquals("subscribed",csubl._method);
        assertEquals(channel,csubl._channel);
        assertEquals(session0,csubl._session);
        
        ServerSessionImpl session1 = new ServerSessionImpl(_bayeux);
        ((ServerChannelImpl)_bayeux.getChannel("/foo/*",true)).subscribe(session1);
        assertEquals("subscribed",_subl._method);
        assertEquals("/foo/*",_subl._channel.getId());
        assertEquals(session1,_subl._session);
        
        ServerSessionImpl session2 = new ServerSessionImpl(_bayeux);
        ((ServerChannelImpl)_bayeux.getChannel("/**",true)).subscribe(session2);
        assertEquals("subscribed",_subl._method);
        assertEquals("/**",_subl._channel.getId());
        assertEquals(session2,_subl._session);
        
    }
    
    public void testPublish() throws Exception
    {
        ServerChannelImpl channel = (ServerChannelImpl)_bayeux.getChannel("/foo/bar",true);
        ServerSessionImpl session0 = new ServerSessionImpl(_bayeux);
     
        channel.subscribe(session0);
      
        ServerSessionImpl session1 = new ServerSessionImpl(_bayeux);
        ((ServerChannelImpl)_bayeux.getChannel("/foo/*",true)).subscribe(session1);
        ServerSessionImpl session2 = new ServerSessionImpl(_bayeux);
        ((ServerChannelImpl)_bayeux.getChannel("/**",true)).subscribe(session2);
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
    
    static class CSubL implements ServerChannel.SubscriptionListener
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
