package org.cometd.server;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

public class ServerSessionImpl implements ServerSession
{
    private static final AtomicLong _idCount=new AtomicLong();

    private final BayeuxServerImpl _bayeux;
    private final String _id;
    private final List<ServerSessionListener> _listeners = new CopyOnWriteArrayList<ServerSessionListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ArrayQueue<ServerMessage> _queue=new ArrayQueue<ServerMessage>(8,16,this);
    private final AtomicInteger _batch=new AtomicInteger();
    private final LocalSessionImpl _localSession;
    private final AttributesMap _attributes = new AttributesMap();
    
    private ServerTransport.Dispatcher _dispatcher;
    private transient ServerTransport _adviceTransport;

    private int _maxQueue;
    private volatile boolean _connected;
    private long _timeout=-1;
    private long _interval=-1;
    private boolean _metaConnectDelivery;

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux)
    {
        this(bayeux,null,null);
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux,LocalSessionImpl localSession, String idHint)
    {
        _bayeux=bayeux;
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
    public void deliver(ServerSession from, ServerMessage message)
    {
        ServerMessage.Mutable mutable = message.asMutable();

        if (!_bayeux.extendSend((ServerSessionImpl)from,mutable))
            return;

        doDeliver(from,message);
    }

    /* ------------------------------------------------------------ */
    protected void doDeliver(ServerSession from, ServerMessage message)
    {
        message=extendSend(message);
        if (message==null)
            return;

        for (ServerSessionListener listener : _listeners)
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

        message.incRef();
        _queue.add(message);

        for (ServerSessionListener listener : _listeners)
        {
            if (listener instanceof MessageListener)
            {
                try
                {
                    ((MessageListener)listener).onMessage(this,from,message);
                }
                catch(Exception e)
                {
                    Log.warn(e);
                }
            }
        }

        if (_batch.get() == 0 && _queue.size() > 0)
        {
            if (message.isLazy())
                dispatchLazy();
            else
                dispatch();
        }

    }

    /* ------------------------------------------------------------ */
    protected void connect()
    {
        _connected=true;
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        if (_connected)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.incRef();
            message.setClientId(getId());
            message.setChannelId(Channel.META_DISCONNECT);
            message.setSuccessful(true);
            deliver(this,message);
            if (_queue.size()>0)
                dispatch();
        }
        _bayeux.removeServerSession(this,false);
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
    public boolean setDispatcher(ServerTransport.Dispatcher dispatcher)
    {
        synchronized(_queue)
        {
            if (dispatcher == null)
            {
                // This is the end of a connect
                if (_dispatcher!=null)
                    startIntervalTimeout();
                _dispatcher = null;
                return true;
            }

            if (_dispatcher!=null)
            {
                // This is the reload case: there is an outstanding connect,
                // and the client issues a new connect.
                _dispatcher.cancel();
                _dispatcher=null;
            }

            cancelIntervalTimeout(); 
            
            if (_queue.size()>0)
            {
                startIntervalTimeout();
                return false;
            }

            _dispatcher=dispatcher;
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    protected void dispatch()
    {
        synchronized (_queue)
        {
            if (_dispatcher!=null)
            {
                _dispatcher.dispatch();
                _dispatcher=null;
                startIntervalTimeout();
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
                    _localSession.receive(msg);
                }
            }   
        }
    }

    /* ------------------------------------------------------------ */
    protected void dispatchLazy()
    {
    }

    /* ------------------------------------------------------------ */
    protected void cancelIntervalTimeout()
    {
        // TODO Auto-generated method stub
        
    }

    /* ------------------------------------------------------------ */
    protected void startIntervalTimeout()
    {
        // TODO Auto-generated method stub
        
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
        return _connected;
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
    public Object takeAdvice()
    {
        final ServerTransport transport = _bayeux.getCurrentTransport();
        
        if (transport!=null && transport!=_adviceTransport)
        {
            _adviceTransport=transport;
            return new JSON.Literal("{\"reconnect\":\"retry\",\"interval\":" + 
                    (_interval==-1?transport.getInterval():_interval) + 
                    ",\"timeout\":" + 
                    (_timeout==-1?transport.getTimeout():_timeout) + "}");
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
        _adviceTransport=null;
    }

    /* ------------------------------------------------------------ */
    public void setInterval(long intervalMS)
    {   
        _interval=intervalMS;
        _adviceTransport=null;
    }

    /* ------------------------------------------------------------ */
    protected void removed(boolean timedout)
    {
        _connected=false;
        for (ServerSessionListener listener : _listeners)
        {
            if (listener instanceof ServerSession.RemoveListener)
                ((ServerSession.RemoveListener)listener).removed(this,timedout);
        }
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDelivery(boolean meta)
    {
        _metaConnectDelivery=meta;
    }

    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDelivery()
    {
        return _metaConnectDelivery;
    }

}
