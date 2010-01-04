package org.acme;

import java.io.IOException;
import java.util.Set;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.Channel.ChannelListener;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.BayeuxClient;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

public class BayeuxUsage
{
    BayeuxServer _bayeux;
    BayeuxClient _client;
    
    public void clientUsage() throws IOException
    {
        
        // Add listeners for meta messages
        _client.addListener(new BayeuxClient.MetaListener()
        {
            public void onMetaMessage(BayeuxClient client, Message message, boolean successful,String error)
            {
            }
        });
        
        
        // Listen to all messages on a particular channel
        // THIS DOES *NOT* SEND A SUBSCRIPTION!
        _client.getChannel("/foo/bar").addListener(new Channel.MessageListener()
        {
            public void onMessage(Bayeux bayeux, Channel channel, Message message)
            {
            }
        });
        
        
        // start the client
        _client.handshake(false);
        
        // Get a Channel scoped by the Session
        SessionChannel channel = _client.getSessionChannel("/foo/bar");
        
        // subscribe to all messages on a particular channel
        // THIS DOES SEND A SUBSCRIPTION!
        channel.subscribe(new Channel.MessageListener()
        {   
            public void onMessage(Bayeux bayeux, Channel channel, Message message)
            {
            }
        });
        
        
        // publish a message
        channel.publish("hello world");
        
        
        // because this is a client, the get channel methods are aliases and
        // all channels are scoped by the BayeuxClient
        assert (SessionChannel)_client.getChannel("/foo/bar") == _client.getSessionChannel("/foo/bar");
        
        
    }
    
    
    public void serverUsage()
    {
    
        // Add a listener to notice new session
        _bayeux.addListener(new BayeuxServer.SessionListener()
        {            
            public void sessionAdded(BayeuxServer server, ServerSession channel)
            {
                
            }
            public void sessionRemoved(BayeuxServer server, ServerSession channel, boolean timedout)
            {
            }
        });
        
        // Add a listener to notice new channels
        _bayeux.addListener(new BayeuxServer.ChannelListener()
        {
            public void channelRemoved(BayeuxServer server, Channel channel)
            {                
            }
            
            public void channelAdded(BayeuxServer server, Channel channel)
            {                
            }
        });
        
        // Listen to all subscriptions on the server
        _bayeux.addListener(new BayeuxServer.SubscriptionListener()
        {
            public void unsubscribed(ServerSession session, Channel channel)
            {
            }
            
            public void subscribed(ServerSession session, Channel channel)
            {
            }
        });
        
        // Listen to all subscriptions on a particular channel
        _bayeux.getChannel("/foo/bar").addListener(new ServerChannel.SubscriptionListener()
        {
            public void unsubscribed(ServerSession client, Channel channel)
            {
            }
            
            public void subscribed(ServerSession client, Channel channel)
            {
            }
        });
        
        // Listen to all messages on a particular channel
        _bayeux.getChannel("/foo/bar").addListener(new Channel.MessageListener()
        {
            public void onMessage(Bayeux bayeux, Channel channel, Message message)
            {
            }
        });

        // Listen and potentially CHANGE messages on a particular channel
        _bayeux.getChannel("/foo/bar").addListener(new ServerChannel.PublishListener()
        {
            public void onMessage(ServerMessage.Mutable message)
            {
                // TODO Auto-generated method stub
                
            }
        });
        

        
        // batch the delivery of special message to an arbitrary client:
        final ServerSession session = _bayeux.getServerSession("123456789");
        final ServerMessage.Mutable msg=_bayeux.newMessage();
        msg.setChannelId("/foo/bar");
        msg.setData("something special");
        session.batch(new Runnable()
        {
            public void run()
            {
                session.deliver(msg);
                if (session.isLocalSession())
                    session.getLocalSession().getSessionChannel("/foo/bar").publish("Hello");        
            }
        });
        
        
        
        
        // Create a new Local Session
        final LocalSession local = _bayeux.newLocalSession("testui");
        final SessionChannel channel=local.getSessionChannel("/foo/bar");
        
        // Subscribe to a channel for a local session
        channel.subscribe(new Channel.MessageListener()
        {
            public void onMessage(Bayeux bayeux, Channel channel, Message message)
            {
            }
        });
        
        // batch the publishing of messages from a local client.
        local.batch(new Runnable()
        { 
            public void run()
            {
                channel.publish("hello");
                channel.publish("world");
            }
        });
        
        
        
        
        
        
    }
    
    
}
