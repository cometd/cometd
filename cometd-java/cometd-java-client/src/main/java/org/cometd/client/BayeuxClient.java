package org.cometd.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.SessionChannel.MetaChannelListener;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.log.Log;



/* ------------------------------------------------------------ */
/**
 * When the client is started, a handshake is initialised and the 
 * call to start will block until either a successful handshake or
 * all known servers have been tried.
 */
public class BayeuxClient extends AbstractClientSession implements Bayeux, ClientSession
{
    public static final String BAYEUX_VERSION = "1.0";

    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final TransportRegistry _transportRegistry = new TransportRegistry();
    
    private final HttpURI _server;
    private ClientTransport _transport;
    
    private final Queue<Message.Mutable> _queue = new ConcurrentLinkedQueue<Message.Mutable>();
   
    protected final ScheduledExecutorService _scheduler;    

    private final AtomicInteger _batch = new AtomicInteger();
    private final TransportListener _transportListener = new Listener();
    private final AtomicInteger _messageIds = new AtomicInteger();   
    private final Map<String,Object> _options = new TreeMap<String, Object>();    
    private volatile String _clientId;

    private volatile Map<String,Object> _advice;
    private volatile State _state = State.DISCONNECTED;
    private AtomicBoolean _handshakeBatch = new AtomicBoolean();
    
    private volatile ScheduledFuture<?> _task;

    private Handler _handshakeHandler = new AbstractClientSession.Handler()
    {
        @Override
        public void handle(AbstractClientSession session, Mutable mutable)
        {
            processHandshake(mutable);
        }
    };
    
    private Handler _connectHandler = new Handler()
    {
        @Override
        public void handle(AbstractClientSession session, Mutable mutable)
        {
            processConnect(mutable);
        }
    };

    
    /* ------------------------------------------------------------ */
    public BayeuxClient(String url, ClientTransport... transports)
    {
        this(url,Executors.newSingleThreadScheduledExecutor(), transports);
    }
    
    /* ------------------------------------------------------------ */
    public BayeuxClient(String url, HttpClient httpClient)
    {
        this(url, Executors.newSingleThreadScheduledExecutor(),httpClient);
    }
    /* ------------------------------------------------------------ */
    public BayeuxClient(HttpClient httpClient, String url)
    {
        this(url, Executors.newSingleThreadScheduledExecutor(),httpClient);
    }

    /* ------------------------------------------------------------ */
    public BayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport... transports)
    {
        this(url, Executors.newSingleThreadScheduledExecutor(),null,transports);
    }
    
    /* ------------------------------------------------------------ */
    public BayeuxClient(String url, ScheduledExecutorService scheduler, HttpClient httpClient, ClientTransport... transports)
    {
        this._scheduler = scheduler;
        
        if (transports!=null && transports.length>0)
        {
            for (ClientTransport transport : transports)
                this._transportRegistry.add(transport);
        }
        else
        {
            _transportRegistry.add(new LongPollingTransport(_options,httpClient));
        }
        _server = new HttpURI(url);
        
        ((AbstractClientSession.AbstractSessionChannel)getChannel(Channel.META_HANDSHAKE)).setHandler(_handshakeHandler);
        ((AbstractClientSession.AbstractSessionChannel)getChannel(Channel.META_CONNECT)).setHandler(_connectHandler);
    }

    /* ------------------------------------------------------------ */
    public BayeuxClient(String url)
    {
        this(url,Executors.newSingleThreadScheduledExecutor());
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated
     */
    public BayeuxClient(HttpClient httpClient, Address address, String uri)
    {
        this("http://"+address+uri,httpClient);
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated
     */
    public BayeuxClient(HttpClient httpClient, Address address, String uri, Timer timer)
    {
        this("http://"+address+uri,httpClient);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#doDisconnected()
     */
    @Override
    protected void doDisconnected()
    {
        // TODO Auto-generated method stub
        
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#newChannel(org.cometd.common.ChannelId)
     */
    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId)
    {
        return new ClientSessionChannel(channelId);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#newChannelId(java.lang.String)
     */
    @Override
    protected ChannelId newChannelId(String channelId)
    {
        AbstractSessionChannel channel = (AbstractSessionChannel)_channels.get(channelId);
        return (channel==null)?new ChannelId(channelId):channel.getChannelId();
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
            Message.Mutable message = _queue.poll();
            doSend(message);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Session#disconnect()
     */
    @Override
    public void disconnect()
    {
        if (isConnected())
        {
            Message.Mutable message = newMessage();
            message.setClientId(getId());
            message.setChannel(Channel.META_DISCONNECT);
            message.setId(_idGen.incrementAndGet());
            send(message);
            while (_batch.get()>0)
                endBatch();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public List<String> getAllowedTransports()
    {
        return _transportRegistry.getAllowedTransports();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getId()
    {
        return _clientId;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<String> getKnownTransportNames()
    {
        return _transportRegistry.getKnownTransports();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#getOption(java.lang.String)
     */
    @Override
    public Object getOption(String qualifiedName)
    {
        return _options.get(qualifiedName);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#getOptionNames()
     */
    @Override
    public Set<String> getOptionNames()
    {
        return _options.keySet();
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getOptions()
    {
        return Collections.unmodifiableMap(_options);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Transport getTransport(String transport)
    {
        return _transportRegistry.getTransport(transport);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void handshake()
    {
        if (_clientId!=null || _handshakeBatch.get())
            throw new IllegalStateException();
        
        List<String> allowed = getAllowedTransports();
        
        Message.Mutable message = newMessage();
        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,allowed);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        message.setId(_idGen.incrementAndGet());
        
        _batch.set(1);
        _handshakeBatch.set(true);
        
        synchronized (_queue)
        {
            updateTransport(_transportRegistry.getTransport(allowed.get(0)));
            _state=State.HANDSHAKING;
            doSend(message);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isConnected()
    {
        return _clientId!=null && _state==State.CONNECTED;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#setOption(java.lang.String, java.lang.Object)
     */
    @Override
    public void setOption(String qualifiedName, Object value)
    {
        _options.put(qualifiedName,value);   
    }    
    
    /* ------------------------------------------------------------ */
    protected void doSend(Message.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if(!extension.sendMeta(this,message))
                    return;
        }
        else
        {
            for (Extension extension : _extensions)
                if(!extension.send(this,message))
                    return;
        }
        
        System.out.println("> "+message);
        
        _transport.send(message);
    }

    /* ------------------------------------------------------------ */
    protected void updateTransport(ClientTransport transport)
    {
        if (_transport==transport)
            return;
        
        if (_transport != null)
        {
            _transport.reset();
            _transport=null;
        }
        
        transport.init(this, _server, _transportListener);
        _transport=transport;
    }

    /* ------------------------------------------------------------ */
    protected Message.Mutable newMessage()
    {
        if (_transport!=null)
            return _transport.newMessage();
        return new HashMapMessage();
    }

    /* ------------------------------------------------------------ */
    protected void processHandshake(Message handshake)
    {
        boolean successful = handshake.isSuccessful();

        if (successful)
        {
            ClientTransport transport = _transportRegistry.negotiate((Object[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD), BayeuxClient.BAYEUX_VERSION).get(0);
            if (transport == null)
            {
                // TODO: notify and stop
                throw new UnsupportedOperationException();
            }
            else if (transport != _transport)
                updateTransport(transport);
            
            updateState(State.CONNECTING);
            _clientId = handshake.getClientId();
        }
        
        followAdvice();
    }
    
    /* ------------------------------------------------------------ */
    protected void processConnect(Message handshake)
    {
        boolean successful = handshake.isSuccessful();
        
        if (successful)
        {
            updateState(State.CONNECTED);
            if (_handshakeBatch.getAndSet(false))
                endBatch();
        }
        else
            updateState(State.DISCONNECTED);
        followAdvice();   
    }
 
    /* ------------------------------------------------------------ */
    protected void receive(List<Message.Mutable> incomingMessages)
    {
        for (Message message : incomingMessages)
        {
            System.out.println("< "+message);
            if ("/meta/subscribe".equals(message.getChannel()))
                System.out.println("match");
            receive(message,(Message.Mutable)message);   
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void send(Message.Mutable message)
    {
        if (_batch.get()>0)
            _queue.add(message);
        else
            doSend(message);
    }
    
    /* ------------------------------------------------------------ */
    private void sendConnect()
    {
        Log.debug("Connecting with transport {}", _transport);
        Message.Mutable message = newMessage();
        message.setId(newMessageId());
        message.setClientId(_clientId);
        message.setChannel(Channel.META_CONNECT);
        if (State.CONNECTING.equals(_state))
            message.put(Message.CONNECTION_TYPE_FIELD, _transport.getName());
        message.setId(_idGen.incrementAndGet());
        doSend(message);
    }
    
    /* ------------------------------------------------------------ */
    private void followAdvice()
    {
        String reconnect=null;
        long interval=0;

        Map<String, Object> advice = this._advice;
        if (advice != null)
        {
            if (advice.containsKey(Message.RECONNECT_FIELD))
                reconnect=(String)advice.get(Message.RECONNECT_FIELD);

            if (advice.containsKey(Message.INTERVAL_FIELD))
                interval=((Number)advice.get(Message.INTERVAL_FIELD)).longValue();
        }
        
        // Should we send a connect?
        if (State.CONNECTED.equals(_state) || State.CONNECTING.equals(_state) || Message.RECONNECT_RETRY_VALUE.equals(reconnect))
        {
            // TODO backoff if !connected?
            
            System.out.println("Connect in "+interval);
            _task = _scheduler.schedule(new Runnable()
            {
                public void run()
                {
                    sendConnect();
                }
            }, interval, TimeUnit.MILLISECONDS);
        }
        else if (Message.RECONNECT_RETRY_VALUE.equals(reconnect))
        {
            // TODO backoff?

            System.out.println("Handshake in "+interval);
            _task = _scheduler.schedule(new Runnable()
            {
                public void run()
                {
                    handshake();
                }
            }, interval, TimeUnit.MILLISECONDS);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    private String newMessageId()
    {
        return String.valueOf(_messageIds.incrementAndGet());
    }
    
    /* ------------------------------------------------------------ */
    private void updateState(State newState)
    {
        Log.debug("State change: {} -> {}", _state, newState);
        this._state = newState;
    }
    
    /* ------------------------------------------------------------ */
    protected class ClientSessionChannel extends AbstractSessionChannel
    {
        protected ClientSessionChannel(ChannelId id)
        {
            super(id);
        }

        /* ------------------------------------------------------------ */
        @Override
        public void addListener(SessionChannelListener listener)
        {
            _listeners.add(listener);
        }

        /* ------------------------------------------------------------ */
        @Override
        public ClientSession getSession()
        {
            return BayeuxClient.this;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void publish(Object data)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            Message.Mutable message = newMessage();
            message.setChannel(_id.toString());
            message.setClientId(_clientId);
            message.setData(data);
            message.setId(_idGen.incrementAndGet());
            
            send(message);
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public void publish(Object data,Object id)
        {
            if (_clientId==null)
                throw new IllegalStateException("!handshake");
            
            Message.Mutable message = newMessage();
            message.setChannel(_id.toString());
            message.setClientId(_clientId);
            message.setData(data);
            if (id!=null)
                message.setId(id);
            
            send(message);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.cometd.common.AbstractClientSession.AbstractSessionChannel#sendSubscribe()
         */
        @Override
        protected void sendSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
            message.setClientId(_clientId);
            message.setId(_idGen.incrementAndGet());
            send(message);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.cometd.common.AbstractClientSession.AbstractSessionChannel#sendUnSubscribe()
         */
        @Override
        protected void sendUnSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD,_id.toString());
            message.setClientId(_clientId);
            message.setId(_idGen.incrementAndGet());

            send(message);
        }
    }
    
    private class Listener implements TransportListener
    {
        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            receive(messages);
        }

        @Override
        public void onConnectException(Throwable x)
        {
            // TODO Auto-generated method stub
        }

        @Override
        public void onException(Throwable x)
        {
            // TODO Auto-generated method stub
        }

        @Override
        public void onExpire()
        {
            // TODO Auto-generated method stub
        }

        @Override
        public void onProtocolError()
        {
            // TODO Auto-generated method stub
        }
    }
    
    private enum State
    {
        HANDSHAKING, CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED
    }


}
