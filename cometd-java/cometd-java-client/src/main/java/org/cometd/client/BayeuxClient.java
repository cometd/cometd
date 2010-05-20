package org.cometd.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;



/* ------------------------------------------------------------ */
/**
 * When the client is started, a handshake is initialised and the
 * call to start will block until either a successful handshake or
 * all known servers have been tried.
 */
public class BayeuxClient extends AbstractClientSession implements Bayeux, ClientSession, TransportListener
{
    public static final String BAYEUX_VERSION = "1.0";

    protected final ScheduledExecutorService _scheduler;
    private volatile Map<String,Object> _advice;

    private long _backoffInc=1000;
    private long _backoffMax=30000;
    private int _backoffTries=0;

    private volatile String _clientId;

    private Handler _connectHandler = new Handler()
    {
        @Override
        public void handle(AbstractClientSession session, Mutable mutable)
        {
            processConnect(mutable);
        }
    };
    private Map<String, ExpirableCookie> _cookies = new ConcurrentHashMap<String, ExpirableCookie>();
    private Handler _disconnectHandler = new Handler()
    {
        @Override
        public void handle(AbstractClientSession session, Mutable mutable)
        {
            processDisconnect(mutable);
        }
    };

    private AtomicBoolean _handshakeBatch = new AtomicBoolean();
    private Handler _handshakeHandler = new AbstractClientSession.Handler()
    {
        @Override
        public void handle(AbstractClientSession session, Mutable mutable)
        {
            processHandshake(mutable);
        }
    };
    private final Map<String,Object> _options = new TreeMap<String, Object>();

    private final Queue<Message.Mutable> _queue = new ConcurrentLinkedQueue<Message.Mutable>();

    private Buffer _scheme;
    private final HttpURI _server;
    private volatile State _state = State.DISCONNECTED;

    private ClientTransport _transport;

    private final TransportRegistry _transportRegistry = new TransportRegistry();
    private final HttpClient _privateHttpClient;
    private final AtomicBoolean _batchInTransit = new AtomicBoolean();

    private final Listener _listener = new Listener();
    private final Listener _batchListener = new Listener()
    {
        @Override
        public void complete()
        {
            _batchInTransit.set(false);
            if (!isBatching() && _queue.size()>0)
                sendBatch();
        }
    };



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
    public BayeuxClient(HttpClient httpClient, String url)
    {
        this(url, Executors.newSingleThreadScheduledExecutor(),httpClient);
    }

    /* ------------------------------------------------------------ */
    public BayeuxClient(String url)
    {
        this(url,Executors.newSingleThreadScheduledExecutor());
    }

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
            _privateHttpClient=null;
        }
        else
        {
            if (httpClient==null)
            {
                Log.debug("created private HttpClient for "+this);
                httpClient=_privateHttpClient=new HttpClient();
            }
            else
                _privateHttpClient=null;

            if (!httpClient.isRunning())
            {
                try
                {
                    httpClient.start();
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
            _transportRegistry.add(new LongPollingTransport(_options,httpClient));
        }
        _server = new HttpURI(url);

        _scheme = (HttpSchemes.HTTPS.equals(_server.getScheme()))?HttpSchemes.HTTPS_BUFFER:HttpSchemes.HTTP_BUFFER;

        ((AbstractClientSession.AbstractSessionChannel)getChannel(Channel.META_HANDSHAKE)).setHandler(_handshakeHandler);
        ((AbstractClientSession.AbstractSessionChannel)getChannel(Channel.META_CONNECT)).setHandler(_connectHandler);
        ((AbstractClientSession.AbstractSessionChannel)getChannel(Channel.META_DISCONNECT)).setHandler(_disconnectHandler);
    }

    /* ------------------------------------------------------------ */
    /**
     * Customize an Exchange. Called when an exchange is about to be sent to
     * allow Cookies and Credentials to be customized. Default implementation
     * sets any cookies
     */
    public void customize(HttpExchange exchange)
    {
        StringBuilder builder = null;
        for (String cookieName : _cookies.keySet())
        {
            if (builder == null)
                builder = new StringBuilder();
            else
                builder.append("; ");

            // Expiration is handled by getCookie()
            ExpirableCookie cookie = getCookie(cookieName);
            if (cookie != null)
            {
                builder.append(QuotedStringTokenizer.quote(cookie.getName()));
                builder.append("=");
                builder.append(QuotedStringTokenizer.quote(cookie.getValue()));
            }
        }

        if (builder != null)
            exchange.setRequestHeader(HttpHeaders.COOKIE,builder.toString());

        if (_scheme!=null)
            exchange.setScheme(_scheme);
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
            updateState(State.DISCONNECTING);
            Message.Mutable message = newMessage();
            message.setClientId(getId());
            message.setChannel(Channel.META_DISCONNECT);
            message.setId(newMessageId());
            send(message);
            while (isBatching())
                endBatch();
        }
        else
            updateState(State.DISCONNECTED);

        if (_privateHttpClient!=null)
        {
            try
            {
                _privateHttpClient.stop();
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public List<String> getAllowedTransports()
    {
        return _transportRegistry.getAllowedTransports();
    }

    /* ------------------------------------------------------------ */
    public ExpirableCookie getCookie(String name)
    {
        ExpirableCookie cookie = _cookies.get(name);
        if (cookie != null)
        {
            if (cookie.isExpired())
            {
                _cookies.remove(name);
                cookie = null;
            }
        }
        return cookie == null ? null : cookie;
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
    /**
     * @see #onConnectException(Throwable)
     * @see #onException(Throwable)
     * @see #onExpire()
     */
    @Override
    public void handshake()
    {
        handshake(null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see #onConnectException(Throwable)
     * @see #onException(Throwable)
     * @see #onExpire()
     */
    @Override
    public void handshake(Map<String, Object> template)
    {
        if (_privateHttpClient!=null && !_privateHttpClient.isRunning())
        {
            try
            {
                _privateHttpClient.start();
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
        }

        List<String> allowed = getAllowedTransports();

        Message.Mutable message = newMessage();
        if (template!=null)
            message.putAll(template);
        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,allowed);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        message.setId(newMessageId());

        // TODO: review this: too many batching variables...
        if (!_handshakeBatch.getAndSet(true))
            //_batch.set(1);
            startBatch();

        updateTransport(_transportRegistry.getTransport(allowed.get(0)));
        updateState(State.HANDSHAKING);
        doSend(_listener,message);
    }

    /* ------------------------------------------------------------ */
    /** Blocking Handshake.
     * Unlike {@link #handshake()}, this call blocks until the handshake
     * succeeds or fails.
     * @param waitMs The time to wait in ms for the completion.
     * @return The state that the client is in after the handshake completes, failed or
     * the wait expired.
     */
    public State handshake(long waitMs)
    {
        handshake(null);
        waitFor(waitMs,State.CONNECTED,State.CONNECTING, State.DISCONNECTED, State.UNCONNECTED);
        return _state;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see #onConnectException(Throwable)
     * @see #onException(Throwable)
     * @see #onExpire()
     */
    public State handshake(Map<String,Object> template, long waitMs)
    {
        handshake(template);
        waitFor(waitMs,State.CONNECTED,State.CONNECTING, State.DISCONNECTED, State.UNCONNECTED);
        return _state;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isConnected()
    {
        return _clientId!=null && _state==State.CONNECTED;
    }

    /* ------------------------------------------------------------ */
    public void setCookie(String name, String value)
    {
        ExpirableCookie expirableCookie = new ExpirableCookie(name,value, -1L);
        _cookies.put(name, expirableCookie);
    }

    /* ------------------------------------------------------------ */
    public void setCookie(String name, String value, int maxAge)
    {
        long expirationTime = System.currentTimeMillis();
        if (maxAge < 0)
            expirationTime = -1L;
        else
            expirationTime += TimeUnit.SECONDS.toMillis(maxAge);

        ExpirableCookie expirableCookie = new ExpirableCookie(name,value, expirationTime);
        _cookies.put(name, expirableCookie);
    }

    /* ------------------------------------------------------------ */

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
    /**
     * @see org.cometd.common.AbstractClientSession#doDisconnected()
     */
    @Override
    protected void doDisconnected()
    {
        // TODO Auto-generated method stub

    }

    /* ------------------------------------------------------------ */
    protected void doSend(TransportListener listener, final Message.Mutable... messages)
    {
        for (final Mutable message : messages)
        {
            if (!extendSend(message))
                continue;

            if (_clientId != null)
                message.setClientId(_clientId);
        }
        _transport.send(listener,messages);
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
        AbstractSessionChannel channel = getChannels().get(channelId);
        return (channel==null)?new ChannelId(channelId):channel.getChannelId();
    }

    /* ------------------------------------------------------------ */
    protected Message.Mutable newMessage()
    {
        if (_transport!=null)
            return _transport.newMessage();
        return new HashMapMessage();
    }

    /* ------------------------------------------------------------ */
    protected void processConnect(Message handshake)
    {
        boolean successful = handshake.isSuccessful();

        if (successful)
        {
            switch(_state)
            {
                case CONNECTED:
                    break;
                case CONNECTING:
                    updateState(State.CONNECTED);
                    break;
            }
            if (_handshakeBatch.getAndSet(false))
            {
                endBatch();
            }
        }
        else
        {
            updateState(State.UNCONNECTED);
            _backoffTries++;
        }
        followAdvice();
    }

    /* ------------------------------------------------------------ */
    protected void processDisconnect(Message handshake)
    {
        boolean successful = handshake.isSuccessful();

        if (successful)
            updateState(State.DISCONNECTED);
    }

    /* ------------------------------------------------------------ */
    protected void processHandshake(Message handshake)
    {
        boolean successful = handshake.isSuccessful();

        if (successful)
        {
            _backoffTries=0;
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
        else
            _backoffTries++;

        followAdvice();
    }

    /* ------------------------------------------------------------ */
    protected void receive(List<Message.Mutable> incomingMessages)
    {
        for (Message message : incomingMessages)
        {
            receive(message,(Message.Mutable)message);
        }
    }

    /* ------------------------------------------------------------ */
    protected void send(Message.Mutable message)
    {
        _queue.add(message);
        if (!isBatching())
            sendBatch();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractClientSession#sendBatch()
     */
    @Override
    protected void sendBatch()
    {
        if (_batchInTransit.compareAndSet(false,true))
        {
            int size=_queue.size();
            Message.Mutable[] messages=new Message.Mutable[size];

            for (int i=0;i<size;i++)
                messages[i] = _queue.poll();
            doSend(_batchListener,messages);
        }
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

        transport.init(this,_server);
        _transport=transport;
    }

    /* ------------------------------------------------------------ */
    private void followAdvice()
    {
        String reconnect=Message.RECONNECT_RETRY_VALUE;
        long interval=0;

        Map<String, Object> advice = this._advice;
        if (advice != null)
        {
            if (advice.containsKey(Message.RECONNECT_FIELD))
                reconnect=(String)advice.get(Message.RECONNECT_FIELD);

            if (advice.containsKey(Message.INTERVAL_FIELD))
                interval=((Number)advice.get(Message.INTERVAL_FIELD)).longValue();
        }

        // TODO backoff interval!
        switch(_state)
        {
            case HANDSHAKING:
                scheduleHandshake(interval);
                break;

            case CONNECTING:
                sendConnect();
                break;

            case CONNECTED:
                _backoffTries=0;
                scheduleConnect(interval);
                break;

            case UNCONNECTED:
                if (Message.RECONNECT_RETRY_VALUE.equals(reconnect))
                {
                    scheduleConnect(interval);
                    break;
                }

                if (Message.RECONNECT_HANDSHAKE_VALUE .equals(reconnect))
                {
                    scheduleHandshake(interval);
                    break;
                }

            case DISCONNECTING:
            case DISCONNECTED:

        }
    }

    /* ------------------------------------------------------------ */
    private void scheduleConnect(long interval)
    {
        long backOff=_backoffTries*_backoffInc;
        if (backOff>_backoffMax)
            backOff=_backoffMax;

        _scheduler.schedule(new Runnable()
        {
            public void run()
            {
                sendConnect();
            }
        }, interval+backOff, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------ */
    private void scheduleHandshake(long interval)
    {
        long backOff=_backoffTries*_backoffInc;
        if (backOff>_backoffMax)
            backOff=_backoffMax;

        _scheduler.schedule(new Runnable()
        {
            public void run()
            {
                handshake();
            }
        }, interval+backOff, TimeUnit.MILLISECONDS);
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
        message.setId(newMessageId());
        doSend(_listener,message);
    }

    /* ------------------------------------------------------------ */
    private void updateState(State newState)
    {
        Log.debug("State change: {} -> {}", _state, newState);
        synchronized (_queue)
        {
            this._state = newState;
            _queue.notifyAll();
        }
    }

    /* ------------------------------------------------------------ */
    /** Wait for client state.
     * Wait for one of several states to be achieved by the client.
     * @param waitMs the time in MS to wait for a state
     * @param states The states to wait for
     * @return True if a waited for state is achieved.
     */
    public boolean waitFor(long waitMs,State... states)
    {
        if (states.length==0)
            throw new IllegalArgumentException("no stats");

        long start = System.currentTimeMillis();

        synchronized (_queue)
        {
            while (System.currentTimeMillis()-start<waitMs)
            {
                for (State s : states)
                    if (_state==s)
                        return true;
                try
                {
                    _queue.wait(waitMs);
                }
                catch(InterruptedException e)
                {
                    long now=System.currentTimeMillis();
                    waitMs-=now-start;
                    start=now;
                }
            }

            for (State s : states)
                if (_state==s)
                    return true;
            return false;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return super.toString()+":"+_server+":"+_state;
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
            Message.Mutable message = newMessage();
            message.setChannel(getId());
            message.setData(data);
            message.setId(newMessageId());

            send(message);
        }

        /* ------------------------------------------------------------ */
        @Override
        public void publish(Object data,Object id)
        {
            Message.Mutable message = newMessage();
            message.setChannel(getId());
            message.setData(data);
            if (id!=null)
                message.setId(id);

            send(message);
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return super.toString()+"@"+_clientId;
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
            message.put(Message.SUBSCRIPTION_FIELD,getId());
            message.setId(newMessageId());
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
            message.put(Message.SUBSCRIPTION_FIELD,getId());
            message.setId(newMessageId());

            send(message);
        }
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class ExpirableCookie
    {
        private final String _name;
        private final String _value;
        private final long _expirationTime;

        private ExpirableCookie(String name, String value, long expirationTime)
        {
            _name=name;
            _value=value;
            _expirationTime = expirationTime;
        }

        private boolean isExpired()
        {
            if (_expirationTime < 0) return false;
            return System.currentTimeMillis() >= _expirationTime;
        }

        /* ------------------------------------------------------------ */
        /** Get the name.
         * @return the name
         */
        public String getName()
        {
            return _name;
        }

        /* ------------------------------------------------------------ */
        /** Get the value.
         * @return the value
         */
        public String getValue()
        {
            return _value;
        }

        /* ------------------------------------------------------------ */
        /** Get the expirationTime.
         * @return the expirationTime
         */
        public long getExpirationTime()
        {
            return _expirationTime;
        }


    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class Listener implements TransportListener
    {
        public void complete()
        {
        }

        @Override
        public void onConnectException(Throwable x)
        {
            BayeuxClient.this.onConnectException(x);
            if (State.CONNECTED.equals(_state))
                updateState(State.UNCONNECTED);
            _backoffTries++;
            followAdvice();
            complete();
        }

        @Override
        public void onException(Throwable x)
        {
            BayeuxClient.this.onException(x);
            if (State.CONNECTED.equals(_state))
                updateState(State.UNCONNECTED);
            _backoffTries++;
            followAdvice();
            complete();
        }

        @Override
        public void onExpire()
        {
            BayeuxClient.this.onExpire();
            if (State.CONNECTED.equals(_state))
                updateState(State.UNCONNECTED);
            _backoffTries++;
            followAdvice();
            complete();
        }

        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            BayeuxClient.this.onMessages(messages);
            receive(messages);
            complete();
        }

        @Override
        public void onProtocolError(String info)
        {
            BayeuxClient.this.onProtocolError(info);
            if (State.CONNECTED.equals(_state))
                updateState(State.UNCONNECTED);
            _backoffTries++;
            followAdvice();
            complete();
        }
    }

    @Override
    public void onConnectException(Throwable x)
    {
        Log.warn("onConnectException "+this,x);
    }

    @Override
    public void onException(Throwable x)
    {
        Log.warn("onException "+this,x);
    }

    @Override
    public void onExpire()
    {
        Log.warn("onExpire "+this);
    }

    @Override
    public void onMessages(List<Message.Mutable> messages)
    {
    }

    @Override
    public void onProtocolError(String info)
    {
        Log.warn("onProtocolError:"+info+" "+this);
    }


    public enum State
    {
        CONNECTED, CONNECTING, DISCONNECTED, DISCONNECTING, HANDSHAKING, UNCONNECTED
    };



}
