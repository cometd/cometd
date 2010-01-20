package org.cometd.client;

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
import org.cometd.bayeux.client.BayeuxClient.Extension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.AttributesMap;

public class ClientSessionImpl implements ClientSession
{
    private final BayeuxClientImpl _bayeux;
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final List<ClientSessionListener> _listeners = new CopyOnWriteArrayList<ClientSessionListener>();
    private final Queue<Message> _queue = new ConcurrentLinkedQueue<Message>();
   
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, ClientSessionChannel> _channels = new ConcurrentHashMap<String, ClientSessionChannel>();
    private final AtomicInteger _batch = new AtomicInteger();
    
    private volatile String _clientId;
    private volatile ClientTransport _transport;

    
    protected ClientSessionImpl(BayeuxClientImpl bayeux)
    {
        _bayeux=bayeux;
    }
    
    @Override
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    @Override
    public void addListener(ClientSessionListener listener)
    {
        _listeners.add(listener);
    }

    @Override
    public SessionChannel getChannel(String channelId)
    {
        ClientSessionChannel channel = _channels.get(channelId);
        if (channel==null)
        {
            ClientSessionChannel new_channel=new ClientSessionChannel(channelId);
            channel=_channels.putIfAbsent(channelId,new_channel);
            if (channel==null)
                channel=new_channel;
        }
        return channel;
    }

    @Override
    public void handshake(boolean async) throws IOException
    {
        if (_clientId!=null)
            throw new IllegalStateException();
        
        Message.Mutable message = newMessage();
        message.setChannelId(Channel.META_HANDSHAKE);
        
        doSend(message);
        
    }

    @Override
    public void removeListener(ClientSessionListener listener)
    {
        _listeners.remove(listener);
    }

    @Override
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

    @Override
    public void disconnect()
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void endBatch()
    {
        if (_batch.decrementAndGet()==0)
        {
            int size=_queue.size();
            while(size-->0)
            {
                Message message = _queue.poll();
                doSend(message);
            }
        }
    }
    
    @Override
    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public String getId()
    {
        return _clientId;
    }

    @Override
    public boolean isConnected()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Object removeAttribute(String name)
    {
        Object value = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return value;
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    @Override
    public void startBatch()
    {
        _batch.incrementAndGet();
    }
    
    protected Message.Mutable newMessage()
    {
        if (_transport!=null)
            return _transport.newMessage();
        return new HashMapMessage();
    }
    
    protected void send(Message message)
    {
        if (_batch.get()>0)
            _queue.add(message);
        else
            doSend(message);
    }
    
    protected void doSend(Message message)
    {
        _transport.send(message);
    }
    
    protected class ClientSessionChannel implements SessionChannel
    {
        private final ChannelId _id;
        private CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<MessageListener>();
        
        protected ClientSessionChannel(String channelId)
        {
            _id=new ChannelId(channelId);
        }

        @Override
        public ClientSession getSession()
        {
            return ClientSessionImpl.this;
        }

        @Override
        public void publish(Object data)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            Message.Mutable message = newMessage();
            message.setChannelId(_id.toString());
            message.setClientId(_clientId);
            message.setData(data);
            
            send(message);
        }

        @Override
        public void subscribe(MessageListener listener)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            _subscriptions.add(listener);
            if (_subscriptions.size()==1)
            {
                Message.Mutable message = newMessage();
                message.setChannelId(Channel.META_SUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(_clientId);
                send(message);
            }
        }

        @Override
        public void unsubscribe(MessageListener listener)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            if (_subscriptions.remove(listener) && _subscriptions.size()==0)
            {
                Message.Mutable message = newMessage();
                message.setChannelId(Channel.META_UNSUBSCRIBE);
                message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
                message.setClientId(_clientId);

                send(message);
            }
        }


        @Override
        public void unsubscribe()
        {
            // TODO Auto-generated method stub
            
        }

        @Override
        public String getId()
        {
            return _id.toString();
        }
        
        public ChannelId getChannelId()
        {
            return _id;
        }

        @Override
        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        @Override
        public boolean isMeta()
        {
            return _id.isMeta();
        }

        @Override
        public boolean isService()
        {
            return _id.isService();
        }

        @Override
        public boolean isWild()
        {
            return _id.isWild();
        }

    }

}
