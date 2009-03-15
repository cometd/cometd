// ========================================================================
// Copyright 2006 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.mortbay.cometd;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.ClientListener;
import org.cometd.DeliverListener;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.QueueListener;
import org.cometd.RemoveListener;
import org.mortbay.util.ArrayQueue;
import org.mortbay.util.LazyList;
import org.mortbay.util.ajax.JSON;



/* ------------------------------------------------------------ */
/**
 * 
 * @author gregw
 */
public class ClientImpl implements Client
{
    private String _id;
    private String _type;
    private int _responsesPending;
    private ChannelImpl[] _subscriptions=new ChannelImpl[0]; // copy on write
    private RemoveListener[] _rListeners; // copy on write
    private MessageListener[] _syncMListeners; // copy on write
    private MessageListener[] _asyncMListeners; // copy on write
    private QueueListener[] _qListeners; // copy on write
    private DeliverListener[] _dListeners; // copy on write
    protected AbstractBayeux _bayeux;
    private String _browserId;
    private JSON.Literal _advice;
    private int _batch;
    private int _maxQueue;
    private ArrayQueue<Message> _queue=new ArrayQueue<Message>(8,16,this);
    private long _timeout;
    private int _lag;
    private Extension[] _extensions;
    
    private boolean _deliverViaMetaConnectOnly;
    
    // manipulated and synchronized by AbstractBayeux
    int _adviseVersion;

    /* ------------------------------------------------------------ */
    protected ClientImpl(AbstractBayeux bayeux)
    {
        _bayeux=bayeux;
        _maxQueue=bayeux.getMaxClientQueue();
        _bayeux.addClient(this,null);
        if (_bayeux.isLogInfo())
            _bayeux.logInfo("newClient: "+this);
    }
    
    /* ------------------------------------------------------------ */
    protected ClientImpl(AbstractBayeux bayeux, String idPrefix)
    {
        _bayeux=bayeux;
        _maxQueue=0;
        
        _bayeux.addClient(this,idPrefix);
        
        if (_bayeux.isLogInfo())
            _bayeux.logInfo("newClient: "+this);
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension ext)
    {
        _extensions = (Extension[])LazyList.addToArray(_extensions,ext,Extension.class);
    }

    /* ------------------------------------------------------------ */
    Extension[] getExtensions()
    {
        return _extensions;
    }
    
    /* ------------------------------------------------------------ */
    public void deliver(Client from, String toChannel, Object data, String id)
    {
        MessageImpl message=_bayeux.newMessage();
        message.put(Bayeux.CHANNEL_FIELD,toChannel);
        message.put(Bayeux.DATA_FIELD,data);
        if (id!=null)   
            message.put(Bayeux.ID_FIELD,id);

        Message m=_bayeux.extendSendBayeux(from,message);
        if (m!=null)
            doDelivery(from,m);
        if (m instanceof MessageImpl)
            ((MessageImpl)m).decRef();
    }
    
    /* ------------------------------------------------------------ */
    public void deliverLazy(Client from, String toChannel, Object data, String id)
    {
        MessageImpl message=_bayeux.newMessage();
        message.put(Bayeux.CHANNEL_FIELD,toChannel);
        message.put(Bayeux.DATA_FIELD,data);
        if (id!=null)   
            message.put(Bayeux.ID_FIELD,id);
        message.setLazy(true);
        Message m=_bayeux.extendSendBayeux(from,message);
        if (m!=null)
            doDelivery(from,m);
        if (m instanceof MessageImpl)
            ((MessageImpl)m).decRef();
    }
    
    /* ------------------------------------------------------------ */
    protected void doDelivery(Client from, final Message msg)
    {
        final Message message=_bayeux.extendSendClient(from,this,msg);
        if (message==null)
            return;
        
        MessageListener[] alisteners=null;
        synchronized(this)
        {
            if (_maxQueue<0)
            {
                ((MessageImpl)message).incRef();
                _queue.addUnsafe(message);
            }
            else
            {
                boolean add;
                if (_queue.size()>=_maxQueue)
                {
                    if (_qListeners!=null && _qListeners.length>0)
                    {
                        add=true;
                        for (QueueListener l : _qListeners)
                            add &= l.queueMaxed(from,this,message);
                    }
                    else
                        add=false;
                }
                else
                    add=true;
                
                if (add)
                {
                    ((MessageImpl)message).incRef();
                    _queue.addUnsafe(message);
                }
            }    

            // deliver synchronized
            if (_syncMListeners!=null)
                for (MessageListener l:_syncMListeners)
                    l.deliver(from,this,message);
            alisteners=_asyncMListeners;
             
            if (_batch==0 &&  _responsesPending<1 && _queue.size()>0 && !((MessageImpl)message).isLazy())
                resume();
        }
        
        // deliver unsynchronized
        if (alisteners!=null)
            for (MessageListener l:alisteners)
                l.deliver(from,this,message);
    }

    /* ------------------------------------------------------------ */
    public void doDeliverListeners()
    {
        synchronized (this)
        {
            if (_dListeners!=null)
                for (DeliverListener l:_dListeners)
                    l.deliver(this,_queue);
        }
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDeliveryOnly(boolean deliverViaMetaConnectOnly)
    {
        _deliverViaMetaConnectOnly = deliverViaMetaConnectOnly;
    }
    
    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDeliveryOnly()
    {
        return _deliverViaMetaConnectOnly;
    }
    
    /* ------------------------------------------------------------ */
    public void startBatch()
    {
        synchronized(this)
        {
            _batch++;
        }
    }
    
    /* ------------------------------------------------------------ */
    public void endBatch()
    {
        synchronized(this)
        {
            if (--_batch==0 && _responsesPending<1)
            {
                switch(_queue.size())
                {
                    case 0:
                        break;
                    case 1:
                        if (!((MessageImpl)_queue.get(0)).isLazy())
                            resume();
                        break;
                    default:
                        // TODO check entire queue for non-lazy messages!
                        resume();
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    public String getConnectionType()
    {
        return _type;
    }
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.mortbay.cometd.C#getId()
     */
    public String getId()
    {
        return _id;
    }
   
    /* ------------------------------------------------------------ */
    public boolean hasMessages()
    {
        return _queue.size()>0;
    }
   
    /* ------------------------------------------------------------ */
    public boolean hasNonLazyMessages()
    {
        synchronized (this)
        {
            for (int i=_queue.size();i-->0;)
            {
                if (!((MessageImpl)_queue.getUnsafe(i)).isLazy())
                    return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isLocal()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.cometd.Client#disconnect()
     */
    public void disconnect()
    {
        synchronized(this)
        {
            if (_bayeux.hasClient(_id))
                remove(false);
        }
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see dojox.cometd.Client#remove(boolean)
     */
    public void remove(boolean timeout)
    {
        Client client=_bayeux.removeClient(_id); 
        
        if (client!=null && _bayeux.isLogInfo())
            _bayeux.logInfo("Remove client "+client+" timeout="+timeout); 
        
        final String browser_id;
        final RemoveListener[] listeners;
        synchronized(this)
        {  
            browser_id=_browserId;
            _browserId=null;
            listeners=_rListeners;
        }

        if (browser_id!=null)
            _bayeux.clientOffBrowser(browser_id,_id);
        if (listeners!=null)
            for (RemoveListener l:listeners)
                l.removed(_id, timeout);
        
        resume();
    }
    
    /* ------------------------------------------------------------ */
    public int responded()
    {
        synchronized(this)
        {
            return _responsesPending--;
        }
    }

    /* ------------------------------------------------------------ */
    public int responsePending()
    {
        synchronized(this)
        {
            return ++_responsesPending;
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Called by deliver to resume anything waiting on this client.
     */
    public void resume()
    {
    }
    
    /* ------------------------------------------------------------ */
    /*
     * @return the number of messages queued
     */
    public int getMessages()
    {
        return _queue.size();
    }
    
    /* ------------------------------------------------------------ */
    public List<Message> takeMessages()
    {
        synchronized(this)
        {
            ArrayList<Message> list = new ArrayList<Message>(_queue);
            _queue.clear();
            return list;
        }
    }
    

    /* ------------------------------------------------------------ */
    public void returnMessages(List<Message> messages)
    {
        synchronized(this)
        {
            _queue.addAll(0,messages);
        }
    }
        
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _id;
    }

    /* ------------------------------------------------------------ */
    protected void addSubscription(ChannelImpl channel)
    {
        synchronized (this)
        {
            _subscriptions=(ChannelImpl[])LazyList.addToArray(_subscriptions,channel,null);
        }
    }

    /* ------------------------------------------------------------ */
    protected void removeSubscription(ChannelImpl channel)
    {
        synchronized (this)
        {
            _subscriptions=(ChannelImpl[])LazyList.removeFromArray(_subscriptions,channel);
        }
    }

    /* ------------------------------------------------------------ */
    protected void setConnectionType(String type)
    {
        synchronized (this)
        {
            _type=type;
        }
    }

    /* ------------------------------------------------------------ */
    protected void setId(String id)
    {
        synchronized (this)
        {
            _id=id;
        }
    }

    /* ------------------------------------------------------------ */
    protected void unsubscribeAll()
    {
        ChannelImpl[] subscriptions;
        synchronized(this)
        {
            _queue.clear();
            subscriptions=_subscriptions;
            _subscriptions=new ChannelImpl[0];
        }
        for (ChannelImpl channel : subscriptions)
            channel.unsubscribe(this);
        
    }

    /* ------------------------------------------------------------ */
    public void setBrowserId(String id)
    {
        if (_browserId!=null && !_browserId.equals(id))
            _bayeux.clientOffBrowser(_browserId,_id);
        _browserId=id;
        if (_browserId!=null)
            _bayeux.clientOnBrowser(_browserId,_id);
    }

    /* ------------------------------------------------------------ */
    public String getBrowserId()
    {
        return _browserId;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean equals(Object o)
    {
    	if (!(o instanceof Client))
    		return false;
    	return getId().equals(((Client)o).getId());
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the advice specific for this Client
     * @return advice specific for this client or null
     */
    public JSON.Literal getAdvice()
    {
    	return _advice;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param advice specific for this client
     */
    public void setAdvice(JSON.Literal advice)
    {
    	_advice=advice;
    }
    

    /* ------------------------------------------------------------ */
    public void addListener(ClientListener listener)
    {
        synchronized(this)
        {
            if (listener instanceof MessageListener)
            {
                if (listener instanceof MessageListener.Synchronous)
                    _syncMListeners=(MessageListener[])LazyList.addToArray(_syncMListeners,listener,MessageListener.class);
                else
                    _asyncMListeners=(MessageListener[])LazyList.addToArray(_asyncMListeners,listener,MessageListener.class);
            }

            if (listener instanceof RemoveListener)
                _rListeners=(RemoveListener[])LazyList.addToArray(_rListeners,listener,RemoveListener.class);

            if (listener instanceof QueueListener)
                _qListeners=(QueueListener[])LazyList.addToArray(_qListeners,listener,QueueListener.class);

            if (listener instanceof DeliverListener)
                _dListeners=(DeliverListener[])LazyList.addToArray(_dListeners,listener,DeliverListener.class);
        }
    }

    /* ------------------------------------------------------------ */
    public void removeListener(ClientListener listener)
    {
        synchronized(this)
        {
            if (listener instanceof MessageListener)
            {
                _syncMListeners=(MessageListener[])LazyList.removeFromArray(_syncMListeners,listener);
                _asyncMListeners=(MessageListener[])LazyList.removeFromArray(_asyncMListeners,listener);
            }

            if (listener instanceof RemoveListener)
                _rListeners=(RemoveListener[])LazyList.removeFromArray(_rListeners,listener);

            if (listener instanceof QueueListener)
                _qListeners=(QueueListener[])LazyList.removeFromArray(_qListeners,listener);
        }
    }

    /* ------------------------------------------------------------ */
    public long getTimeout() 
    {
    	return _timeout;
    }

    /* ------------------------------------------------------------ */
    public void setTimeout(long timeoutMS) 
    {
    	_timeout=timeoutMS;
    }

    /* ------------------------------------------------------------ */
    public void setMaxQueue(int maxQueue)
    {
        _maxQueue=maxQueue;
    }
    
    /* ------------------------------------------------------------ */
    public int getMaxQueue()
    {
        return _maxQueue;
    }
    
    /* ------------------------------------------------------------ */
    public Queue<Message> getQueue()
    {
        return _queue;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.mortbay.cometd.ext.TimesyncExtension
     * @return The lag in ms as measured by an extension like the TimesyncExtension
     */
    public int getLag()
    {
        return _lag;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.mortbay.cometd.ext.TimesyncExtension
     * @param lag in ms
     */
    public void setLag(int lag)
    {
        _lag = lag;
    }
    
    
}
