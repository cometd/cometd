package org.cometd.server;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AbstractClientSession;
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

    @Override
    public void receive(Message.Mutable message)
    {
        super.receive(message);
        if (Channel.META_DISCONNECT.equals(message.getChannel()) && message.isSuccessful())
            _session = null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#newChannel(org.cometd.bayeux.ChannelId)
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
        handshake(null);
    }

    /* ------------------------------------------------------------ */
    public void handshake(Map<String, Object> template)
    {
        if (_session!=null)
            throw new IllegalStateException();

        ServerMessage.Mutable message = _bayeux.newMessage();
        if (template!=null)
            message.putAll(template);
        message.setChannel(Channel.META_HANDSHAKE);
        message.setId(newMessageId());

        ServerSessionImpl session = new ServerSessionImpl(_bayeux,this,_idHint);

        doSend(session,message);

        ServerMessage reply = message.getAssociated();
        if (reply!=null && reply.isSuccessful())
        {
            _session=session;

            message = _bayeux.newMessage();
            message.setChannel(Channel.META_CONNECT);
            message.setClientId(_session.getId());
            message.put(Message.ADVICE_FIELD,LOCAL_ADVICE);
            message.setId(newMessageId());

            doSend(session,message);
            reply = message.getAssociated();
            if (!reply.isSuccessful())
                _session=null;
        }
        message.setAssociated(null);
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        if (_session!=null)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_DISCONNECT);
            message.setId(newMessageId());
            send(_session,message);
            while (isBatching())
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
    public boolean isHandshook()
    {
        return _session!=null && _session.isHandshook();
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
        if (isBatching())
            _queue.add(message);
        else
            doSend(session,message);
    }

    /* ------------------------------------------------------------ */
    /** Send a message (to the server).
     * <p>
     * Extends and sends the message without batching.
     * @param from The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void doSend(ServerSessionImpl from,ServerMessage.Mutable message)
    {
        if (!extendSend(message))
            return;

        if (_session!=null)
            message.setClientId(_session.getId());

        ServerMessage.Mutable reply = _bayeux.handle(from,message);

        if (reply != null)
        {
            reply = _bayeux.extendReply(from,(_session!=null&&_session.isHandshook())?_session:null,reply);
            if (reply != null)
                receive(reply);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** A channel scoped to this local session
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
            publish(data, null);
        }

        /* ------------------------------------------------------------ */
        public void publish(Object data, String messageId)
        {
            if (_session == null)
                throw new IllegalStateException("!handshake");

            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(getId());
            message.setData(data);
            if (messageId != null)
                message.setId(messageId);

            send(_session, message);
            message.setAssociated(null);
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return super.toString()+"@"+LocalSessionImpl.this.toString();
        }

        @Override
        protected void sendSubscribe()
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,getId());
            message.setClientId(LocalSessionImpl.this.getId());
            message.setId(newMessageId());

            send(_session,message);
            message.setAssociated(null);
        }

        @Override
        protected void sendUnSubscribe()
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,getId());
            message.setId(newMessageId());

            send(_session,message);
            message.setAssociated(null);
        }
    }

}
