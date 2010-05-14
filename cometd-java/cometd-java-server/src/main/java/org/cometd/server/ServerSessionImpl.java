package org.cometd.server;

import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.server.AbstractServerTransport.Dispatcher;
import org.cometd.server.transports.HttpTransport;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.util.thread.Timeout.Task;

public class ServerSessionImpl implements ServerSession
{
    private static final AtomicLong _idCount=new AtomicLong();

    private final BayeuxServerImpl _bayeux;
    private final Logger _logger;
    private final String _id;
    private final List<ServerSessionListener> _listeners = new CopyOnWriteArrayList<ServerSessionListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ArrayQueue<ServerMessage> _queue=new ArrayQueue<ServerMessage>(8,16,this);
    private final AtomicInteger _batch=new AtomicInteger();
    private final LocalSessionImpl _localSession;
    private final AttributesMap _attributes = new AttributesMap();
    private final AtomicBoolean _connected = new AtomicBoolean();
    private final AtomicBoolean _handshook = new AtomicBoolean();
    private final Set<ServerChannelImpl> _subscribedTo = Collections.newSetFromMap(new ConcurrentHashMap<ServerChannelImpl, Boolean>());
    
    private AbstractServerTransport.Dispatcher _dispatcher;
    private transient ServerTransport _advisedTransport;

    private int _maxQueue=-1;
    private long _timeout=-1;
    private long _interval=-1;
    private long _maxInterval;
    private long _maxLazy=-1;
    private boolean _metaConnectDelivery;
    private long _accessed=-1;

    private volatile long _intervalTimestamp;
    private volatile boolean _lazyDispatch;

    private Task _lazyTask;

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux)
    {
        this(bayeux,null,null);
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux,LocalSessionImpl localSession, String idHint)
    {
        _bayeux=bayeux;
        _logger=bayeux.getLogger();
        _localSession=localSession;

        StringBuilder id=new StringBuilder(30);
        int len=20;
        if (idHint!=null)
        {
            len+=idHint.length()+1;
            id.append(idHint);
            id.append('_');
        }
        int index=id.length();

        while (id.length()<len)
        {
            long random=_bayeux.randomLong();
            id.append(Long.toString(random<0?-random:random,36));
        }

        id.insert(index,Long.toString(_idCount.incrementAndGet(),36));

        _id=id.toString();
        
        HttpTransport transport=(HttpTransport)_bayeux.getCurrentTransport();
        if (transport!=null)
            _intervalTimestamp=System.currentTimeMillis()+transport.getMaxInterval();
    }
    
    /* ------------------------------------------------------------ */
    protected void sweep(long now)
    {
        if (_intervalTimestamp!=0 && now>_intervalTimestamp)
        {
            if (_logger.isDebugEnabled())
                _logger.debug("Expired interval "+ServerSessionImpl.this);
            synchronized (_queue)
            {
                if (_dispatcher!=null)
                    _dispatcher.cancelDispatch();
            }
            _bayeux.removeServerSession(ServerSessionImpl.this,true);
        }
    }
    
    /* ------------------------------------------------------------ */
    protected List<Extension> getExtensions()
    {
        return _extensions;
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    /* ------------------------------------------------------------ */
    public void batch(Runnable batch)
    {
        try
        {
            startBatch();
            batch.run();
        }
        finally
        {
            endBatch();
        }
    }

    /* ------------------------------------------------------------ */
    public void deliver(Session from, ServerMessage message)
    {
        ServerMessage.Mutable mutable = message.asMutable();

        if (!_bayeux.extendSend((ServerSessionImpl)from,mutable))
            return;
        
        if (from instanceof LocalSession)
            doDeliver(((LocalSession)from).getServerSession(),message);
        else
            doDeliver((ServerSession)from,message);
    }

    /* ------------------------------------------------------------ */
    public void deliver(Session from, String channelId, Object data, Object id)
    {
        ServerMessage.Mutable mutable = _bayeux.newMessage();
        mutable.setChannel(channelId);
        mutable.setData(data);
        mutable.setId(id);
        deliver(from,mutable);
    }

    /* ------------------------------------------------------------ */
    protected void doDeliver(ServerSession from, ServerMessage message)
    {
        message=extendSend(message);
        if (message==null)
            return;

        for (ServerSessionListener listener : _listeners)
        {
            try
            {
                if (listener instanceof MaxQueueListener && _maxQueue >=0 && _queue.size() >= _maxQueue)
                {
                    if (!((MaxQueueListener)listener).queueMaxed(this,from,message))
                        return;
                }
                if (listener instanceof MessageListener)
                {
                    if (!((MessageListener)listener).onMessage(this,from,message))
                        return; 
                }
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
        }

        _queue.add(message);


        if (_batch.get() == 0 && _queue.size() > 0)
        {
            if (message.isLazy())
                dispatchLazy();
            else
                dispatch();
        }
    }

    /* ------------------------------------------------------------ */
    protected void handshake()
    {
        _handshook.set(true);
    }
    
    /* ------------------------------------------------------------ */
    protected void connect(long timestamp)
    {
        synchronized (_queue)
        {
            cancelIntervalTimeout(); 
            _connected.set(true);
            
            if (_accessed==-1)
            {
                HttpTransport transport=(HttpTransport)_bayeux.getCurrentTransport();
                
                if (transport!=null)
                {
                    _maxQueue=transport.getOption("maxQueue",-1);

                    _maxInterval=_interval>=0?(_interval+transport.getMaxInterval()-transport.getInterval()):transport.getMaxInterval();
                    _maxLazy=transport.getMaxLazyTimeout();

                    if (_maxLazy>0)
                    {
                        _lazyTask=new Timeout.Task()
                        {
                            @Override
                            public void expired()
                            {
                                _lazyDispatch=false;
                                dispatch();
                            }

                            @Override
                            public String toString()
                            {
                                return "LazyTask@"+getId();
                            }
                        };
                    }
                }
                
            }
            _accessed=timestamp;
        }
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {       
        boolean connected=_bayeux.removeServerSession(this,false);
        if (connected)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setClientId(getId());
            message.setChannel(Channel.META_DISCONNECT);
            message.setSuccessful(true);
            deliver(this,message);
            if (_queue.size()>0)
                dispatch();
        }
    }
    

    /* ------------------------------------------------------------ */
    public void endBatch()
    {
        if (_batch.decrementAndGet()==0 && _queue.size()>0)
            dispatch();
    }

    /* ------------------------------------------------------------ */
    public LocalSession getLocalSession()
    {
        return _localSession;
    }

    /* ------------------------------------------------------------ */
    public boolean isLocalSession()
    {
        return _localSession!=null;
    }

    /* ------------------------------------------------------------ */
    public void startBatch()
    {
        _batch.incrementAndGet();
    }

    /* ------------------------------------------------------------ */
    public void addListener(ServerSessionListener listener)
    {
        _listeners.add((ServerSessionListener)listener);
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return _id;
    }

    /* ------------------------------------------------------------ */
    public Queue<ServerMessage> getQueue()
    {
        return _queue;
    }

    /* ------------------------------------------------------------ */
    public void removeListener(ServerSessionListener listener)
    {
        _listeners.remove(listener);
    }
    
    /* ------------------------------------------------------------ */
    public boolean setDispatcher(AbstractServerTransport.Dispatcher dispatcher)
    {
        synchronized(_queue)
        {
            if (dispatcher == null)
            {
                // This is the end of a connect
                Dispatcher old=_dispatcher;
                _dispatcher = null;
                return old!=null;
            }

            if (_dispatcher!=null && _dispatcher!=dispatcher)
            {
                throw new IllegalStateException();
            }

            if (_queue.size()>0)
                return false;

            _dispatcher=dispatcher;
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    public void dispatch()
    {
        synchronized (_queue)
        {
            if (_lazyDispatch && _lazyTask!=null)
                _bayeux.cancelTimeout(_lazyTask);

            Dispatcher dispatcher=_dispatcher;
            if (dispatcher!=null)
            {
                _dispatcher=null;
                dispatcher.dispatch();
                return;
            }
        }
        
        // do local delivery
        if  (_localSession!=null && _queue.size()>0)
        {
            for (ServerSessionListener listener : _listeners)
            {
                if (listener instanceof ServerSession.DeQueueListener)
                    ((ServerSession.DeQueueListener)listener).deQueue(this);
            }

            for (int s=_queue.size();s-->0;)
            {
                ServerMessage msg=_queue.poll();
                if (msg!=null)
                {           
                    _localSession.receive(msg,msg.asMutable());
                }
            }   
        }
    }

    /* ------------------------------------------------------------ */
    public void dispatchLazy()
    {
        synchronized (_queue)
        {
            if (_maxLazy==0)
                dispatch();
            else if (_maxLazy>0 && !_lazyDispatch)
            {
                _lazyDispatch=true;
                _bayeux.startTimeout(_lazyTask,_accessed%_maxLazy);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void cancelDispatch()
    {
        synchronized (_queue)
        {
            Dispatcher dispatcher=_dispatcher;
            if (dispatcher!=null)
            {
                _dispatcher=null;
                dispatcher.cancelDispatch();
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    public void cancelIntervalTimeout()
    {
        _intervalTimestamp=0;
    }

    /* ------------------------------------------------------------ */
    public void startIntervalTimeout()
    {
        _intervalTimestamp=System.currentTimeMillis()+_maxInterval;
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
    public Object removeAttribute(String name)
    {
        Object old = getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    /* ------------------------------------------------------------ */
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    public boolean isConnected()
    {
        return _connected.get();
    }
    
    /* ------------------------------------------------------------ */
    public boolean isHandshook()
    {
        return _handshook.get();
    }

    /* ------------------------------------------------------------ */
    protected boolean extendRecv(ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension ext: _extensions)
                if (!ext.rcvMeta(this,message))
                    return false;
        }
        else
        {
            for (Extension ext: _extensions)
                if (!ext.rcv(this,message))
                    return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected ServerMessage extendSend(ServerMessage message)
    {
        if (message.isMeta())
        {
            for (Extension ext : _extensions)
                if (!ext.sendMeta(this,message.asMutable()))
                    return null;
        }
        else
        {
            for (Extension ext : _extensions)
            {
                message=ext.send(this,message);
                if (message==null)
                    return null;
            }
        }
        
        return message;
    }

    /* ------------------------------------------------------------ */
    public Object getAdvice()
    {
        final ServerTransport transport = _bayeux.getCurrentTransport();
        if (transport==null)
            return null;
        return new JSON.Literal("{\"reconnect\":\"retry\",\"interval\":" + 
                (_interval==-1?transport.getInterval():_interval) + 
                ",\"timeout\":" + 
                (_timeout==-1?transport.getTimeout():_timeout) + "}");
    }

    /* ------------------------------------------------------------ */
    public void reAdvise()
    {
        _advisedTransport=null;
    }
    
    /* ------------------------------------------------------------ */
    public Object takeAdvice()
    {
        final ServerTransport transport = _bayeux.getCurrentTransport();
                
        if (transport!=null && transport!=_advisedTransport)
        {
            _advisedTransport=transport;
            return getAdvice();
        }
        
        // advice has not changed, so return null.
        return null;
    }

    /* ------------------------------------------------------------ */
    public long getTimeout()
    {   
        return _timeout;
    }

    /* ------------------------------------------------------------ */
    public long getInterval()
    {   
        return _interval;
    }
    
    /* ------------------------------------------------------------ */
    public void setTimeout(long timeoutMS)
    {   
        _timeout=timeoutMS;
        _advisedTransport=null;
    }

    /* ------------------------------------------------------------ */
    public void setInterval(long intervalMS)
    {   
        _interval=intervalMS;
        _advisedTransport=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param timedout
     * @return True if the session was connected.
     */
    protected boolean removed(boolean timedout)
    {
        boolean connected = _connected.getAndSet(false);
        boolean handshook = _handshook.getAndSet(false);
        if (connected || handshook)
        {
            for (ServerChannelImpl channel : _subscribedTo)
            {
                channel.unsubscribe(this);
            }
            
            for (ServerSessionListener listener : _listeners)
            {
                if (listener instanceof ServerSession.RemoveListener)
                    ((ServerSession.RemoveListener)listener).removed(this,timedout);
            }
        }
        return connected;
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDeliveryOnly(boolean meta)
    {
        _metaConnectDelivery=meta;
    }

    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDeliveryOnly()
    {
        return _metaConnectDelivery;
    }

    /* ------------------------------------------------------------ */
    public void dequeue()
    {
        synchronized (_queue)
        {
            for (ServerSessionListener listener : _listeners)
            {
                if (listener instanceof ServerSession.DeQueueListener)
                    ((ServerSession.DeQueueListener)listener).deQueue(this);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected void subscribedTo(ServerChannelImpl channel)
    {
        _subscribedTo.add(channel);
    }
    
    /* ------------------------------------------------------------ */
    protected void unsubscribedTo(ServerChannelImpl channel)
    {
        _subscribedTo.remove(channel);
    }
    
    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append('\n');
        
        for (ServerSessionListener child : _listeners)
        {
            b.append(indent);
            b.append(" +-");
            b.append(child);
            b.append('\n');
        }
        
        if (isLocalSession())
        {
            b.append(indent);
            b.append(" +-");
            _localSession.dump(b,indent+"   ");
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _id;
    }

}
