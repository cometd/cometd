package org.cometd.server;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.SessionChannel.SubscriptionListener;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.ChannelId;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.ajax.JSON;


/* ------------------------------------------------------------ */
/** A LocalSession implementation.
 * <p>
 * This session is local to the {@link BayeuxServer} instance and 
 * communicates with the server without any serialization.
 * The normal Bayeux meta messages are exchanged between the LocalSession
 * and the ServerSession.
 */
public class LocalSessionImpl implements LocalSession
{
    private static final Object LOCAL_ADVICE=JSON.parse("{\"interval\":-1}");

    private final BayeuxServerImpl _bayeux;
    private final String _idHint;
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, LocalChannel> _channels = new ConcurrentHashMap<String, LocalChannel>();
    private final AtomicInteger _batch = new AtomicInteger();
    private final Queue<ServerMessage.Mutable> _queue = new ConcurrentLinkedQueue<ServerMessage.Mutable>();
    private final List<LocalChannel> _wild = new CopyOnWriteArrayList<LocalChannel>();
    
    private ServerSessionImpl _session;

    /* ------------------------------------------------------------ */
    protected LocalSessionImpl(BayeuxServerImpl bayeux,String idHint)
    {
        _bayeux=bayeux;
        _idHint=idHint;
    }

    /* ------------------------------------------------------------ */
    public ServerSession getServerSession()
    {
        if (_session==null)
            throw new IllegalStateException("!handshake");
        return _session;
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    /* ------------------------------------------------------------ */
    public SessionChannel getChannel(String channelId)
    {
        LocalChannel channel = _channels.get(channelId);
        if (channel==null)
        {
            ChannelId id = _bayeux.newChannelId(channelId);
            LocalChannel new_channel=new LocalChannel(id);
            channel=_channels.putIfAbsent(channelId,new_channel);
            if (channel==null)
                channel=new_channel;
        }
        if (channel.isWild())
            _wild.add(channel);
        return channel;
    }

    /* ------------------------------------------------------------ */
    public void handshake()
    {
        if (_session!=null)
            throw new IllegalStateException();
        
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.incRef();
        message.setChannelId(Channel.META_HANDSHAKE);
        
        ServerSessionImpl session = new ServerSessionImpl(_bayeux,this,_idHint);
        
        doSend(session,message);
        
        ServerMessage reply = message.getAssociated();
        if (reply!=null && reply.isSuccessful())
        {   
            _session=session;
            
            message.clear();
            message.setChannelId(Channel.META_CONNECT);
            message.setClientId(_session.getId());
            message.put(Message.ADVICE_FIELD,LOCAL_ADVICE);

            doSend(session,message);
            reply = message.getAssociated();
            if (!reply.isSuccessful())
                _session=null;
        }
        message.setAssociated(null);
        message.decRef();
    }

    /* ------------------------------------------------------------ */
    public void startBatch()
    {
        _batch.incrementAndGet();
    }

    /* ------------------------------------------------------------ */
    public void endBatch()
    {  
        if (_batch.decrementAndGet()==0)
        {
            int size=_queue.size();
            while(size-->0)
            {
                ServerMessage.Mutable message = _queue.poll();
                doSend(_session,message);
                message.decRef();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void batch(Runnable batch)
    {
        startBatch();
        try
        {
            batch.run();
        }
        finally
        {
            endBatch();
        }
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        if (_session!=null)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setClientId(getId());
            message.setChannelId(Channel.META_DISCONNECT);
            send(_session,message);
            message.decRef();
            while (_batch.get()>0)
                endBatch();
        }
    }

    /* ------------------------------------------------------------ */
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    /* ------------------------------------------------------------ */
    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNameSet();
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        if (_session==null)
            throw new IllegalStateException("!handshake");
        return _session.getId();
    }

    /* ------------------------------------------------------------ */
    public boolean isConnected()
    {
        return _session!=null && _session.isConnected();
    }

    /* ------------------------------------------------------------ */
    public Object removeAttribute(String name)
    {
        Object old = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    /* ------------------------------------------------------------ */
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "L:"+(_session==null?(_idHint+"?"):_session.getId());
    }

    /* ------------------------------------------------------------ */
    /** Receive a message (from the server)
     * <p>
     * This method calls the receive extensions, the ClientSessionListeners and then
     * the subscribed MessageListeners.
     * @param message the message to receive.
     */
    protected void receive(final ServerMessage message)
    {
        final String id=message.getChannelId();
        final ChannelId channelId=id==null?null:_bayeux.newChannelId(id); 
        final LocalChannel channel= channelId==null?null:_channels.get(channelId.toString());


        
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if (!extension.rcvMeta(this,message.asMutable()))
                    return;

            String error = (String)message.get(Message.ERROR_FIELD);
            boolean successful = message.isSuccessful();
            
            if (channel!=null)
            {
                for (LocalChannel wild : _wild)
                {       
                    if (wild._id.matches(channel._id))
                    {
                        for (SessionChannel.SessionChannelListener listener : wild._listeners)
                        {
                            if (listener instanceof SessionChannel.ChannelMetaListener)
                                ((SessionChannel.ChannelMetaListener)listener).onMetaMessage(channel,message,successful,error);
                            if (listener instanceof SessionChannel.MessageListener)
                                ((SessionChannel.MessageListener)listener).onMessage(this,message);
                        }
                    }
                }
                
                for (SessionChannel.SessionChannelListener listener : channel._listeners)
                {
                    if (listener instanceof SessionChannel.ChannelMetaListener)
                        ((SessionChannel.ChannelMetaListener)listener).onMetaMessage(channel,message,message.isSuccessful(),error);
                }
            }
        }
        else
        {
            for (Extension extension : _extensions)
                if (!extension.rcv(LocalSessionImpl.this,message.asMutable()))
                    return;

            if (channel!=null)
            {
                for (LocalChannel wild : _wild)
                {       
                    if (wild._id.matches(channel._id))
                    {
                        for (SessionChannel.SessionChannelListener listener : wild._listeners)
                        {
                            if (listener instanceof SessionChannel.MessageListener)
                                ((SessionChannel.MessageListener)listener).onMessage(this,message);
                        }
                    }
                }
            }
        }
        
        if (channel!=null && (channel.isMeta() || message.getData()!=null))
        {
            for (SubscriptionListener listener : channel._subscriptions)
                listener.onMessage(channel,message);
        }

        if (Channel.META_DISCONNECT.equals(id) && message.isSuccessful())
            _session=null;
    }
    
    /* ------------------------------------------------------------ */
    /** Send a message (to the server).
     * <p>
     * This method will either batch the message or call {@link #doSend(ServerSessionImpl, org.cometd.bayeux.server.ServerMessage.Mutable)}
     * @param session The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void send(ServerSessionImpl session,ServerMessage.Mutable message)
    {
        if (_batch.get()>0)
        {
            message.incRef();
            _queue.add(message);
        }
        else
            doSend(session,message);
    }
    
    /* ------------------------------------------------------------ */
    /** Send a message (to the server).
     * <p>
     * Extends and sends the message without batching.
     * @param session The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void doSend(ServerSessionImpl session,ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if(!extension.sendMeta(LocalSessionImpl.this,message))
                    return;
        }
        else
        {
            for (Extension extension : _extensions)
                if(!extension.send(LocalSessionImpl.this,message))
                    return;
        }
        
        ServerMessage reply = _bayeux.handle(session,message);

        if (reply!=null)
        {
            _bayeux.extendReply(session,reply);
            if (reply!=null)
                receive(reply);
        }
    }
    

    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append('\n');

        int leaves=_channels.size();
        int i=0;
        for (LocalChannel child : _channels.values())
        {
            b.append(indent);
            b.append(" +-");
            child.dump(b,indent+((++i==leaves)?"   ":" | "));
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** A SessionChannel scoped to this LocalChannel
     */
    class LocalChannel implements SessionChannel
    {
        private final ChannelId _id;
        private CopyOnWriteArrayList<SubscriptionListener> _subscriptions = new CopyOnWriteArrayList<SubscriptionListener>();
        private CopyOnWriteArrayList<SessionChannelListener> _listeners = new CopyOnWriteArrayList<SessionChannelListener>();

        /* ------------------------------------------------------------ */
        LocalChannel(ChannelId id)
        {
            _id=id;
        }

        /* ------------------------------------------------------------ */
        public ClientSession getSession()
        {
            return LocalSessionImpl.this;
        }
        
        /* ------------------------------------------------------------ */
        public void addListener(SessionChannelListener listener)
        {
            _listeners.add(listener);
        }

        /* ------------------------------------------------------------ */
        public void removeListener(SessionChannelListener listener)
        {
            _listeners.remove(listener);
        }

        /* ------------------------------------------------------------ */
        public void publish(Object data)
        {
            if (_session==null)
                throw new IllegalStateException("!handshake");
            
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannelId(_id.toString());
            message.setClientId(LocalSessionImpl.this.getId());
            message.setData(data);
            
            send(_session,message);
            message.setAssociated(null);
            message.decRef();
        }

        /* ------------------------------------------------------------ */
        public void subscribe(SubscriptionListener listener)
        {
            if (_session==null)
                throw new IllegalStateException("!handshake");
            
            _subscriptions.add(listener);
            if (_subscriptions.size()==1)
            {
                ServerMessage.Mutable message = _bayeux.newMessage();
                message.incRef();
                message.setChannelId(Channel.META_SUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(LocalSessionImpl.this.getId());

                send(_session,message);
                message.setAssociated(null);
                message.decRef();
            }
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe(SubscriptionListener listener)
        {
            if (_session==null)
                throw new IllegalStateException("!handshake");
            
            if (_subscriptions.remove(listener) && _subscriptions.size()==0)
            {
                ServerMessage.Mutable message = _bayeux.newMessage();
                message.incRef();
                message.setChannelId(Channel.META_UNSUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(LocalSessionImpl.this.getId());

                send(_session,message);
                message.setAssociated(null);
                message.decRef();
            }
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe()
        {
            for (SubscriptionListener listener : _subscriptions)
                unsubscribe(listener);
        }

        /* ------------------------------------------------------------ */
        public String getId()
        {
            return _id.toString();
        }

        /* ------------------------------------------------------------ */
        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        /* ------------------------------------------------------------ */
        public boolean isMeta()
        {
            return _id.isMeta();
        }

        /* ------------------------------------------------------------ */
        public boolean isService()
        {
            return _id.isService();
        }

        /* ------------------------------------------------------------ */
        public boolean isWild()
        {
            return _id.isWild();
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return _id+"@"+LocalSessionImpl.this.toString();
        }
        
        /* ------------------------------------------------------------ */
        protected void dump(StringBuilder b,String indent)
        {
            b.append(toString());
            b.append('\n');
           
            for (SessionChannelListener child : _listeners)
            {
                b.append(indent);
                b.append(" +-");
                b.append(child);
                b.append('\n');
            }
            for (SubscriptionListener child : _subscriptions)
            {
                b.append(indent);
                b.append(" +-");
                b.append(child);
                b.append('\n');
            }
        }

    }

}
