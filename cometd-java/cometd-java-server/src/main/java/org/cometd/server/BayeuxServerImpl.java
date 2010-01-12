package org.cometd.server;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.common.ChannelId;
import org.eclipse.jetty.util.ajax.JSON;

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

    private Object _handshakeAdvice=new JSON.Literal("{\"reconnect\":\"handshake\",\"interval\":500}");

    /* ------------------------------------------------------------ */
    BayeuxServerImpl()
    {
        getChannel(Channel.META_HANDSHAKE,true).addListener(new HandshakeHandler());
        getChannel(Channel.META_CONNECT,true).addListener(new ConnectHandler());
        getChannel(Channel.META_SUBSCRIBE,true).addListener(new SubscribeHandler());
        getChannel(Channel.META_UNSUBSCRIBE,true).addListener(new UnsubscribeHandler());
        getChannel(Channel.META_DISCONNECT,true).addListener(new DisconnectHandler());
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
        return new ServerSessionImpl(this);
    }
    
    /* ------------------------------------------------------------ */
    protected ServerSessionImpl newServerSession(LocalSessionImpl local, String idHint)
    {
        return new ServerSessionImpl(this,local,idHint);
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
    protected void recvMessage(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        if (!extendRecv(session,message))
            return;
        if (!session.extendRecv(message))
            return;
        
        String channelId=message.getChannelId();
       
        ServerChannel channel=null;
        if (channelId!=null)
        {
            channel = getChannel(channelId,false);
            if (channel==null && _policy.canCreate(this,session,channelId,message))
                channel = getChannel(channelId,true);
        }

        ServerMessage.Mutable reply=null;
       
        if (channel==null)
        { 
            reply = createReply(message);
            error(reply,channelId==null?"402::no channel":"403:Cannot create");
        }
        else if (channel.isMeta())
        {
            root().doPublish(session,(ServerChannelImpl)channel,message);
            reply = message.getAssociated().asMutable();
        }
        else if (_policy.canPublish(this,session,channel,message))                               
        {
            root().doPublish(session,(ServerChannelImpl)channel,message);
            reply = createReply(message);
            reply.setSuccessful(true);
        }
        else
        {
            reply = createReply(message);
            error(reply,session==null?"402::unknown client":"403:Cannot publish");
        }

        if (reply!=null)
        {
            session.extendSend(reply);
            extendSend(session,reply);
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
                if (!ext.send(message))
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


    /* ------------------------------------------------------------ */
    protected void error(ServerMessage.Mutable reply, String error)
    {
        reply.put(Message.ERROR_FIELD,error);
        reply.setSuccessful(false);
    }

    /* ------------------------------------------------------------ */
    protected ServerMessage.Mutable createReply(ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply=newMessage();
        message.setAssociated(reply);
        
        reply.setChannelId(message.getChannelId());
        String id=message.getId();
        if (id != null)
            reply.setId(id);
        return reply;
    }
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    abstract class HandlerListener implements ServerChannel.ServerChannelListener
    {
        public abstract void onMessage(final ServerSessionImpl from, final ServerMessage.Mutable message);
        
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class HandshakeHandler extends HandlerListener
    {
        public void onMessage(ServerSessionImpl session, final Mutable message)
        {
            if (session==null)
                session = newServerSession();

            ServerMessage.Mutable reply=createReply(message);
            
            if (_policy != null && !_policy.canHandshake(BayeuxServerImpl.this,session,message))
            {
                error(reply,"403::Handshake denied");
                return;
            }

            addServerSession(session);
            
            reply.setSuccessful(true);
            reply.put(Message.CLIENT_FIELD,session.getId());
            reply.put(Message.VERSION_FIELD,"1.0");
            reply.put(Message.MIN_VERSION_FIELD,"1.0");
            reply.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,getAllowedTransports());
            
            Object advice = session.getAdvice();
            if (advice!=null)
                reply.put(Message.ADVICE_FIELD,advice);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ConnectHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);

            if (session == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }

            // receive advice
            Object advice=message.get(Message.ADVICE_FIELD);
            if (advice != null)
            {
                Long timeout=((Map<String,Long>)advice).get("timeout");
                session.setTimeout(timeout==null?0:timeout.longValue());

                Long interval=((Map<String,Long>)advice).get("interval");
                session.setInterval(interval==null?0:interval.longValue());
            }

            // send advice
            advice = session.getAdvice();
            if (advice!=null)
                reply.put(Message.ADVICE_FIELD,advice);
            
            reply.setSuccessful(true);
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (from == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }
            
            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
            
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
                    reply.setSuccessful(true);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class UnsubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (from == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }
            
            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
            
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

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class DisconnectHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (from == null)
            {
                error(reply,"402::Unknown client");
                return;
            }
        }    
    }
}
