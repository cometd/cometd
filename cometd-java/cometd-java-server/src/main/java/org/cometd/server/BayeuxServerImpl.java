package org.cometd.server;

import java.security.SecureRandom;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.BayeuxClient.Extension;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.common.ChannelId;

public class BayeuxServerImpl implements BayeuxServer
{
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<BayeuxServerListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ServerMessagePool _pool = new ServerMessagePool();
    private final ServerChannelImpl _root=new ServerChannelImpl(this,null,new ChannelId("/"));
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<String, ServerSessionImpl>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<String, ServerChannelImpl>();
    private SecurityPolicy _policy=new DefaultSecurityPolicy();

    /* ------------------------------------------------------------ */
    BayeuxServerImpl()
    {
        getChannel(Channel.META_SUBSCRIBE,true).addListener(new SubscribeHandler());
        getChannel(Channel.META_UNSUBSCRIBE,true).addListener(new UnsubscribeHandler());
    }
    
    /* ------------------------------------------------------------ */
    ChannelId newChannelId(String id)
    {
        ServerChannelImpl channel = _channels.get(id);
        if (channel!=null)
            return channel.getChannelId();
        return new ChannelId(id);
    }
    
    /* ------------------------------------------------------------ */
    int randomInt()
    {
        return _random.nextInt();
    }

    /* ------------------------------------------------------------ */
    int randomInt(int n)
    {
        return _random.nextInt(n);
    }

    /* ------------------------------------------------------------ */
    long randomLong()
    {
        return _random.nextLong();
    }

    /* ------------------------------------------------------------ */
    ServerChannelImpl root()
    {
        return _root;
    }

    /* ------------------------------------------------------------ */
    public Transport getCurrentTransport()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public SecurityPolicy getSecurityPolicy()
    {
        return _policy;
    }

    /* ------------------------------------------------------------ */
    public ServerChannel getChannel(String channelId, boolean create)
    {
        ServerChannelImpl channel = _channels.get(channelId);
        if (channel==null && create)
            channel=_root.getChild(new ChannelId(channelId),true);
        return channel;
    }

    /* ------------------------------------------------------------ */
    public ServerSession getSession(String clientId)
    {
        return _sessions.get(clientId);
    }

    /* ------------------------------------------------------------ */
    protected void addServerSession(ServerSessionImpl session)
    {
        _sessions.put(session.getId(),session);
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.SessionListener)
                ((SessionListener)listener).sessionAdded(session);
        }
    }

    /* ------------------------------------------------------------ */
    protected void removeServerSession(ServerSessionImpl session,boolean timedout)
    {
        if(_sessions.remove(session.getId())==session)
        {
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.SessionListener)
                    ((SessionListener)listener).sessionRemoved(session,timedout);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl newServerSession()
    {
        ServerSessionImpl session =new ServerSessionImpl(this);
        addServerSession(session);
        return session;
    }

    /* ------------------------------------------------------------ */
    public LocalSession newLocalSession(String idHint)
    {
        return new LocalSessionImpl(this,idHint);
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable newMessage()
    {
        return _pool.getServerMessage();
    }

    /* ------------------------------------------------------------ */
    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _policy=securityPolicy;
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxServerListener listener)
    {
        if (!(listener instanceof BayeuxServerListener))
            throw new IllegalArgumentException("!BayeuxServerListener");
        _listeners.add((BayeuxServerListener)listener);
    }

    /* ------------------------------------------------------------ */
    public ServerChannel getChannel(String channelId)
    {
        return _channels.get(channelId);
    }

    /* ------------------------------------------------------------ */
    public void removeListener(BayeuxServerListener listener)
    {
        _listeners.remove(listener);
    }


    /* ------------------------------------------------------------ */
    protected void handle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        String channelId=message.getChannelId();
       
        ServerChannel channel = getChannel(channelId,false);
        if (channel==null && _policy.canCreate(this,session,channelId,message))
            channel = getChannel(channelId,true);
        else
        {
            // TODO error response ???
        }
        
        if (channel!=null)
        {
            extendRecv(session,message);
            session.extendRecv(message);
            
            if (channel.isMeta()||_policy.canPublish(this,session,channel,message))                               
                channel.publish(session,message);
            
            ServerMessage reply = message.getAssociated();
            
            if (reply!=null)
            {
                ServerMessage.Mutable mutable = reply.asMutable();
                session.extendSend(mutable);
                extendSend(session,mutable);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    protected boolean extendRecv(ServerSessionImpl from, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension ext: _extensions)
                if (!ext.rcvMeta(from,message))
                    return false;
        }
        else
        {
            for (Extension ext: _extensions)
                if (!ext.rcv(from,message))
                    return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSend(ServerSessionImpl to, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension ext : _extensions)
                if (!ext.sendMeta(to,message))
                    return false;
        }
        else
        {
            for (Extension ext : _extensions)
                if (!ext.send(to,message))
                    return false;
        }
        
        return true;
    }

    /* ------------------------------------------------------------ */
    void addServerChannel(ServerChannelImpl channel)
    {
        ServerChannelImpl old = _channels.putIfAbsent(channel.getId(),channel);
        if (old!=null)
            throw new IllegalStateException();
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.ChannelListener)
            ((ChannelListener)listener).channelAdded(channel);
        }
    }

    /* ------------------------------------------------------------ */
    boolean removeServerChannel(ServerChannelImpl channel)
    {
        if(_channels.remove(channel.getId(),channel))
        {
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.ChannelListener)
                    ((ChannelListener)listener).channelRemoved(channel.getId());
            }
            return true;
        }
        return false;
    }
    
    /* ------------------------------------------------------------ */
    List<BayeuxServerListener> getListeners()
    {
        return _listeners;
    }

    /* ------------------------------------------------------------ */
    public List<String> getAllowedTransports()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public Set<String> getKnownTransportNames()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public Transport getTransport(String transport)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    public void setAllowedTransports(String... transports)
    {
        // TODO Auto-generated method stub
    }


    abstract class HandlerListener implements ServerChannel.ServerChannelListener
    {
        public abstract void onMessage(final ServerSession from, final Mutable message);
        
        protected void error(ServerMessage.Mutable message, String error)
        {
            message.put(Message.ERROR_FIELD,error);
            message.put(Message.SUCCESSFUL_FIELD,Boolean.FALSE);
        }
    }
    
    class SubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSession from, final Mutable message)
        {
            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
         
            ServerMessage.Mutable reply=newMessage();
            message.setAssociated(reply);
            reply.setAssociated(message);
            
            reply.setChannelId(Channel.META_SUBSCRIBE);
            String id=message.getId();
            if (id != null)
                reply.setId(id);
            
            if (subscribe_id==null)
                error(reply,"403::cannot create");
            else
            {
                reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscribe_id);
                if (channel==null && getSecurityPolicy().canCreate(BayeuxServerImpl.this,from,subscribe_id,message))
                    channel = (ServerChannelImpl)getChannel(subscribe_id,true);
                
                if (channel==null)
                    error(reply,"403::cannot create");
                else if (!getSecurityPolicy().canSubscribe(BayeuxServerImpl.this,from,channel,message))
                    error(reply,"403::cannot subscribe");
                else
                {
                    channel.subscribe((ServerSessionImpl)from);
                    reply.put(Message.SUCCESSFUL_FIELD,Boolean.TRUE);
                }
            }
        }
    }

    
    class UnsubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSession from, final Mutable message)
        {
            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
         
            ServerMessage.Mutable reply=newMessage();
            message.setAssociated(reply);
            reply.setAssociated(message);
            
            reply.setChannelId(Channel.META_UNSUBSCRIBE);
            String id=message.getId();
            if (id != null)
                reply.setId(id);
            
            if (subscribe_id==null)
                error(reply,"400::no channel");
            else
            {
                reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);
                
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscribe_id);
                if (channel==null)
                    error(reply,"400::no channel");
                else
                {
                    channel.unsubscribe((ServerSessionImpl)from);
                    reply.put(Message.SUCCESSFUL_FIELD,Boolean.TRUE);
                }
            }
        }
    }
}
