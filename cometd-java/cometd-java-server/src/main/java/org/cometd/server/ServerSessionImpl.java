package org.cometd.server;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

public class ServerSessionImpl implements ServerSession
{
    private static final AtomicLong _idCount=new AtomicLong();
    
    private final BayeuxServerImpl _bayeux;
    private final String _id;
    private final List<ServerSessionListener> _listeners = new CopyOnWriteArrayList<ServerSessionListener>();
    private ArrayQueue<ServerMessage> _queue=new ArrayQueue<ServerMessage>(8,16,this);
    private int _maxQueue;
    private final AtomicInteger _batch=new AtomicInteger();
    private LocalSession _localSession;

    protected ServerSessionImpl(BayeuxServerImpl bayeux)
    {
        this(bayeux,null);
    }
    
    protected ServerSessionImpl(BayeuxServerImpl bayeux,String idHint)
    {
        _bayeux=bayeux;
        
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
            id.append(Long.toString(_bayeux.randomLong(),36));
            
        id.insert(index,Long.toString(_idCount.incrementAndGet(),36));
        
        _id=id.toString();
    }
    
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
    
    public void deliver(ServerSession from, ServerMessage message)
    {
        Message.Mutable mutable = ((ServerMessageImpl)message).asMutable();
        
        if (!_bayeux.extendSend(from,mutable))
            return;
        
        doDeliver(from,message);
    }
    
    void doDeliver(ServerSession from, ServerMessage message)
    {
        
        if (_maxQueue >=0 && _queue.size() >= _maxQueue)
        {
            for (ServerSessionListener listener : _listeners)
            {
                if (listener instanceof MaxQueueListener)
                {
                    if (!((MaxQueueListener)listener).queueMaxed(from,this,message))
                        return;
                }
                if (listener instanceof QueueListener)
                {
                    message = ((QueueListener)listener).onQueue(from,this,message);
                    if (message==null)
                        return;
                }
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
                    ((MessageListener)listener).onMessage(this,message);
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
    
    public void disconnect()
    {
        // TODO Auto-generated method stub
        
    }

    public void endBatch()
    {
        if (_batch.decrementAndGet()==0 && _queue.size()>0)
            dispatch();
    }

    public LocalSession getLocalSession()
    {
        return _localSession;
    }

    public boolean isLocalSession()
    {
        return _localSession!=null;
    }

    public void startBatch()
    {
        _batch.incrementAndGet();
    }

    public void addListener(SessionListener listener)
    {
        if (!(listener instanceof ServerSessionListener))
            throw new IllegalArgumentException("!ServerSessionListener");
        _listeners.add((ServerSessionListener)listener);
    }

    public String getId()
    {
        return _id;
    }

    public Queue<ServerMessage> getQueue()
    {
        return _queue;
    }

    public void removeListener(SessionListener listener)
    {
        _listeners.remove(listener);
    }


    protected void dispatch()
    {
        
    }
    
    protected void dispatchLazy()
    {
        
    }
}
