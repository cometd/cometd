package org.cometd.server;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.ChannelId;
import org.eclipse.jetty.util.ajax.JSON;


/* ------------------------------------------------------------ */
/** A LocalSession implementation.
 * <p>
 * This session is local to the {@link BayeuxServer} instance and 
 * communicates with the server without any serialization.
 * The normal Bayeux meta messages are exchanged between the LocalSession
 * and the ServerSession.
 */
public class LocalSessionImpl extends AbstractClientSession implements LocalSession
{
    private static final Object LOCAL_ADVICE=JSON.parse("{\"interval\":-1}");
    private final Queue<ServerMessage.Mutable> _queue = new ConcurrentLinkedQueue<ServerMessage.Mutable>();
    private final BayeuxServerImpl _bayeux;
    private final String _idHint;
    
    private ServerSessionImpl _session;

    /* ------------------------------------------------------------ */
    protected LocalSessionImpl(BayeuxServerImpl bayeux,String idHint)
    {
        _bayeux=bayeux;
        _idHint=idHint;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#doDisconnected()
     */
    @Override
    protected void doDisconnected()
    {
        _session=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#newChannel(org.cometd.common.ChannelId)
     */
    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId)
    {
        return new LocalChannel(channelId);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#newChannelId(java.lang.String)
     */
    @Override
    protected ChannelId newChannelId(String channelId)
    { 
        return _bayeux.newChannelId(channelId);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#sendBatch()
     */
    @Override
    protected void sendBatch()
    {
        int size=_queue.size();
        while(size-->0)
        {
            ServerMessage.Mutable message = _queue.poll();
            doSend(_session,message);
            message.decRef();
        }
    }

    /* ------------------------------------------------------------ */
    public ServerSession getServerSession()
    {
        if (_session==null)
            throw new IllegalStateException("!handshake");
        return _session;
    }

    /* ------------------------------------------------------------ */
    public void handshake()
    {
        if (_session!=null)
            throw new IllegalStateException();
        
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.incRef();
        message.setChannel(Channel.META_HANDSHAKE);
        message.setId(_idGen.incrementAndGet());
        
        ServerSessionImpl session = new ServerSessionImpl(_bayeux,this,_idHint);
        
        doSend(session,message);
        
        ServerMessage reply = message.getAssociated();
        if (reply!=null && reply.isSuccessful())
        {   
            _session=session;
            
            message.clear();
            message.setChannel(Channel.META_CONNECT);
            message.setClientId(_session.getId());
            message.put(Message.ADVICE_FIELD,LOCAL_ADVICE);
            message.setId(_idGen.incrementAndGet());

            doSend(session,message);
            reply = message.getAssociated();
            if (!reply.isSuccessful())
                _session=null;
        }
        message.setAssociated(null);
        message.decRef();
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        if (_session!=null)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannel(Channel.META_DISCONNECT);
            message.setId(_idGen.incrementAndGet());
            send(_session,message);
            message.decRef();
            while (_batch.get()>0)
                endBatch();
        }
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
    @Override
    public String toString()
    {
        return "L:"+(_session==null?(_idHint+"?"):_session.getId());
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
        
        if (_session!=null)
            message.setClientId(_session.getId());
            
        ServerMessage reply = _bayeux.handle(session,message);

        if (reply!=null)
        {
            _bayeux.extendReply(session,reply);
            if (reply!=null)
                receive(reply,reply.asMutable());
        }
    }
    

    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append('\n');

        int leaves=_channels.size();
        int i=0;
        for (AbstractSessionChannel child : _channels.values())
        {
            b.append(indent);
            b.append(" +-");
            ((LocalChannel)child).dump(b,indent+((++i==leaves)?"   ":" | "));
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** A SessionChannel scoped to this LocalChannel
     */
    protected class LocalChannel extends AbstractSessionChannel
    {
        /* ------------------------------------------------------------ */
        LocalChannel(ChannelId id)
        {
            super(id);
        }

        /* ------------------------------------------------------------ */
        public ClientSession getSession()
        {
            return LocalSessionImpl.this;
        }

        /* ------------------------------------------------------------ */
        public void publish(Object data)
        {
            if (_session==null)
                throw new IllegalStateException("!handshake");
            
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannel(_id.toString());
            message.setData(data);
            
            send(_session,message);
            message.setAssociated(null);
            message.decRef();
        }
        
        /* ------------------------------------------------------------ */
        public void publish(Object data,Object id)
        {
            if (_session==null)
                throw new IllegalStateException("!handshake");
            
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannel(_id.toString());
            message.setData(data);
            if (id!=null)
                message.setId(id);
            
            send(_session,message);
            message.setAssociated(null);
            message.decRef();
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

        @Override
        protected void sendSubscribe()
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
            message.setClientId(LocalSessionImpl.this.getId());
            message.setId(_idGen.incrementAndGet());

            send(_session,message);
            message.setAssociated(null);
            message.decRef();
        }

        @Override
        protected void sendUnSubscribe()
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
            message.setId(_idGen.incrementAndGet());

            send(_session,message);
            message.setAssociated(null);
            message.decRef();
        }

    }

}
