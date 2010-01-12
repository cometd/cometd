package org.cometd.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.BayeuxClient.Extension;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.ChannelId;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.ajax.JSON;

public class LocalSessionImpl implements LocalSession
{
    private static final Object LOCAL_ADVICE=JSON.parse("{\"interval\":-1}");

    private final BayeuxServerImpl _bayeux;
    private final String _idHint;
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final List<ClientSessionListener> _listeners = new CopyOnWriteArrayList<ClientSessionListener>();
    private final AttributesMap _attributes = new AttributesMap();
    private final Map<String, LocalChannel> _channels = new HashMap<String, LocalChannel>();
    
    private ServerSessionImpl _session;
    
    LocalSessionImpl(BayeuxServerImpl bayeux,String idHint)
    {
        _bayeux=bayeux;
        _idHint=idHint;
    }
    
    public ServerSession getServerSession()
    {
        return _session;
    }

    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void addListener(ClientSessionListener listener)
    {
        _listeners.add(listener);
    }

    public SessionChannel getChannel(String channelId)
    {
        LocalChannel channel = _channels.get(channelId);
        if (channel==null)
        {
            ChannelId id = _bayeux.newChannelId(channelId);
            if (id.isWild())
                throw new IllegalStateException();
            channel=new LocalChannel(id);
            _channels.put(channelId,channel);
        }
        return channel;
    }

    public void handshake(boolean async) throws IOException
    {
        if (_session!=null)
            throw new IllegalStateException();
        
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setChannelId(Channel.META_HANDSHAKE);
        
        ServerSessionImpl session = new ServerSessionImpl(_bayeux,this,_idHint);
        
        sendToServer(session,Channel.META_HANDSHAKE,message);
        
        ServerMessage reply = message.getAssociated();
        if (reply.isSuccessful())
        {   
            _session=session;
            
            message.clear();
            message.setChannelId(Channel.META_CONNECT);
            message.setClientId(_session.getId());
            message.put(Message.ADVICE_FIELD,LOCAL_ADVICE);

            sendToServer(session,Channel.META_HANDSHAKE,message);
            reply = message.getAssociated();
            if (!reply.isSuccessful())
                _session=null;
        }
    }

    public void removeListener(ClientSessionListener listener)
    {
        _listeners.remove(listener);
    }

    public void batch(Runnable batch)
    {
        // TODO Auto-generated method stub
        
    }

    public void disconnect()
    {
        // TODO send a disconnect message ?
        if (_session!=null)
            _session.disconnect();
        _session=null;
    }

    public void endBatch()
    {
        // TODO Auto-generated method stub     
    }

    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNameSet();
    }

    public String getId()
    {
        if (_session==null)
            throw new IllegalStateException("!handshake");
        return _session.getId();
    }

    public boolean isConnected()
    {
        return _session!=null && _session.isConnected();
    }

    public Object removeAttribute(String name)
    {
        Object old = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    public void startBatch()
    {
        // TODO Auto-generated method stub
    }

    public String toString()
    {
        return "LocalSession{"+(_session==null?(_idHint+"?"):_session.getId())+"}";
    }

    protected void recvFromServer(ServerMessage message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if (!extension.rcvMeta(this,message.asMutable()))
                    return;
        }
        else
        {
            for (Extension extension : _extensions)
                if (!extension.rcv(LocalSessionImpl.this,message.asMutable()))
                    return;
        }
        
        for (ClientSessionListener listener : _listeners)
        {
            if (listener instanceof ClientSession.MessageListener)
                ((ClientSession.MessageListener)listener).onMessage(this,message);
        }
        
        ChannelId channelId=_bayeux.newChannelId(message.getChannelId());    
        LocalChannel channel = _channels.get(channelId.toString());
        
        if (channel!=null && (channel.isMeta() || message.getData()!=null))
        {
            for (MessageListener listener : channel._subscriptions)
                listener.onMessage(this,message);
        }
    }

    protected void sendToServer(ServerSessionImpl session,String channelId,ServerMessage.Mutable message)
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
        
        _bayeux.recvMessage(session,message);
        
        ServerMessage reply = message.getAssociated();

        if (reply!=null)
            recvFromServer(reply);
    }
    
    class LocalChannel implements SessionChannel
    {
        private final ChannelId _id;
        private CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<MessageListener>();
        
        LocalChannel(ChannelId id)
        {
            _id=id;
        }
        
        public ClientSession getSession()
        {
            return LocalSessionImpl.this;
        }
        
        public void publish(Object data)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannelId(_id.toString());
            message.setClientId(LocalSessionImpl.this.getId());
            message.setData(data);
            
            sendToServer(_session,_id.toString(),message);
        }

        public void subscribe(MessageListener listener)
        {
            _subscriptions.add(listener);
            if (_subscriptions.size()==1)
            {
                ServerMessage.Mutable message = _bayeux.newMessage();
                message.setChannelId(Channel.META_SUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(LocalSessionImpl.this.getId());

                sendToServer(_session,Channel.META_SUBSCRIBE,message);
            }
        }

        public void unsubscribe(MessageListener listener)
        {
            if (_subscriptions.remove(listener) && _subscriptions.size()==0)
            {
                ServerMessage.Mutable message = _bayeux.newMessage();
                message.setChannelId(Channel.META_UNSUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(LocalSessionImpl.this.getId());

                sendToServer(_session,Channel.META_UNSUBSCRIBE,message);
            }
        }

        public void unsubscribe()
        {
            for (MessageListener listener : _subscriptions)
                unsubscribe(listener);
        }

        public String getId()
        {
            return _id.toString();
        }

        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        public boolean isMeta()
        {
            return _id.isMeta();
        }

        public boolean isService()
        {
            return _id.isService();
        }

        public boolean isWild()
        {
            return _id.isWild();
        }
        
    }

}
