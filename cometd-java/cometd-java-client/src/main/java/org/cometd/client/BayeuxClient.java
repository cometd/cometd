package org.cometd.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.TransportException;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
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
    
    private final Queue<Message> _queue = new ConcurrentLinkedQueue<Message>();
   
    protected final ScheduledExecutorService _scheduler;    

    private final AtomicInteger _batch = new AtomicInteger();
    private final TransportListener _transportListener = new Listener();
    private final AtomicInteger _messageIds = new AtomicInteger();   
    private final Map<String,Object> _options = new TreeMap<String, Object>();    
    private volatile String _clientId;

    private volatile Map<String,Object> _advice;
    private volatile State _state = State.DISCONNECTED;
    private volatile ScheduledFuture<?> _task;
    
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
    }

    /* ------------------------------------------------------------ */
    public BayeuxClient(String url)
    {
        this(url,Executors.newSingleThreadScheduledExecutor());
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
            Message message = _queue.poll();
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
        // TODO Auto-generated method stub
        
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
    public void handshake() throws IOException
    {
        if (_clientId!=null)
            throw new IllegalStateException();
        
        List<String> allowed = getAllowedTransports();
        
        Message.Mutable message = newMessage();
        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,allowed);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        
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
    protected List<Message.Mutable> applyIncomingExtensions(List<Message.Mutable> messages)
    {
        List<Message.Mutable> result = new ArrayList<Message.Mutable>();
        for (Message.Mutable message : messages)
        {
            for (Extension extension : _extensions)
            {
                try
                {
                    boolean advance;

                    if (message.isMeta())
                        advance = extension.rcvMeta(this, message);
                    else
                        advance = extension.rcv(this, message);

                    if (!advance)
                    {
                        Log.debug("Extension {} signalled to skip message {}", extension, message);
                        message = null;
                        break;
                    }
                }
                catch (Exception x)
                {
                    Log.debug("Exception while invoking extension " + extension, x);
                }
            }
            if (message != null)
                result.add(message);
        }
        return result;
    }
    
    /* ------------------------------------------------------------ */
    protected void doSend(Message message)
    {
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
        Boolean successfulField = (Boolean)handshake.get(Message.SUCCESSFUL_FIELD);
        boolean successful = successfulField != null && successfulField;

        if (successful)
        {
            ClientTransport transport = _transportRegistry.negotiate((String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD), BayeuxClient.BAYEUX_VERSION).get(0);
            if (transport == null)
            {
                // TODO: notify and stop
                throw new UnsupportedOperationException();
            }
            else if (transport != _transport)
                updateTransport(transport);
            

            updateState(State.CONNECTED);
            _clientId = handshake.getClientId();


            followAdvice();
        }
        else
        {

        }
    }

    
    /* ------------------------------------------------------------ */
    protected void receive(List<Message.Mutable> incomingMessages)
    {
        List<Message.Mutable> messages = applyIncomingExtensions(incomingMessages);
        for (Message message : messages)
            receive(message,(Message.Mutable)message);               
    }
    
    /* ------------------------------------------------------------ */
    protected void send(Message message)
    {
        if (_batch.get()>0)
            _queue.add(message);
        else
            doSend(message);
    }
    
    /* ------------------------------------------------------------ */
    private void asyncConnect()
    {
        Log.debug("Connecting with transport {}", _transport);
        Message.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(_clientId);
        request.setChannel(Channel.META_CONNECT);
        request.put(Message.CONNECTION_TYPE_FIELD, _transport.getName());
        send(request);
    }
    
    /* ------------------------------------------------------------ */
    private void followAdvice()
    {
        Map<String, Object> advice = this._advice;
        if (advice != null)
        {
            String action = (String)advice.get(Message.RECONNECT_FIELD);
            if (Message.RECONNECT_RETRY_VALUE.equals(action))
            {
                // Must connect, follow timings in the advice
                Number intervalNumber = (Number)advice.get(Message.INTERVAL_FIELD);
                if (intervalNumber != null)
                {
                    long interval = intervalNumber.longValue();
                    if (interval < 0L)
                        interval = 0L;
                    _task = _scheduler.schedule(new Runnable()
                    {
                        public void run()
                        {
                            asyncConnect();
                        }
                    }, interval, TimeUnit.MILLISECONDS);
                }
            }
            else if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
            {
                // TODO:
                throw new UnsupportedOperationException();
            }
            else if (Message.RECONNECT_NONE_VALUE.equals(action))
            {
                // Do nothing
                // TODO: sure there is nothing more to do ?
            }
            else
            {
                Log.info("Reconnect action {} not supported in advice {}", action, advice);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    private ClientTransport negotiateTransport(String[] requestedTransports)
    {
        ClientTransport transport = _transportRegistry.negotiate(requestedTransports, BAYEUX_VERSION).get(0);
        if (transport == null)
            throw new TransportException("Could not negotiate transport: requested " +
                    Arrays.toString(requestedTransports) +
                    ", available " +
                    Arrays.toString(_transportRegistry.findTransportTypes(BAYEUX_VERSION)));
        return transport;
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
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }


}
