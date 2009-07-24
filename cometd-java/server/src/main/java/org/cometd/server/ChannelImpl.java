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
// ========================================================================

package org.cometd.server;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;
import org.cometd.ChannelListener;
import org.cometd.Client;
import org.cometd.DataFilter;
import org.cometd.Message;
import org.cometd.SubscriptionListener;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 * A Bayuex Channel
 * 
 * @author gregw
 * 
 */
public class ChannelImpl implements Channel
{
    protected AbstractBayeux _bayeux;
    private volatile ClientImpl[] _subscribers=new ClientImpl[0]; // copy on
    // write
    private volatile DataFilter[] _dataFilters=new DataFilter[0]; // copy on
    // write
    private volatile SubscriptionListener[] _subscriptionListeners=new SubscriptionListener[0]; // copy
    // on
    // write
    private ChannelId _id;
    private ConcurrentMap<String,ChannelImpl> _children=new ConcurrentHashMap<String,ChannelImpl>();
    private ChannelImpl _wild;
    private ChannelImpl _wildWild;
    private boolean _persistent;
    private int _split;
    private boolean _lazy;

    /* ------------------------------------------------------------ */
    protected ChannelImpl(String id, AbstractBayeux bayeux)
    {
        _id=new ChannelId(id);
        _bayeux=bayeux;
    }

    /* ------------------------------------------------------------ */
    /**
     * A Lazy channel marks published messages as lazy. Lazy messages are queued
     * but do not wake up waiting clients.
     * 
     * @return true if message is lazy
     */
    public boolean isLazy()
    {
        return _lazy;
    }

    /* ------------------------------------------------------------ */
    /**
     * A Lazy channel marks published messages as lazy. Lazy messages are queued
     * but do not wake up waiting clients.
     * 
     * @param lazy
     *            true if message is lazy
     */
    public void setLazy(boolean lazy)
    {
        _lazy=lazy;
    }

    /* ------------------------------------------------------------ */
    public void addChild(ChannelImpl channel)
    {
        ChannelId child=channel.getChannelId();
        if (!_id.isParentOf(child))
        {
            throw new IllegalArgumentException(_id + " not parent of " + child);
        }

        String next=child.getSegment(_id.depth());

        if ((child.depth() - _id.depth()) == 1)
        {
            // synchronize add and doRemove to avoid concurrent updates
            synchronized(this)
            {
                // add the channel to this channels
                ChannelImpl old=_children.putIfAbsent(next,channel);
                if (old != null)
                    throw new IllegalArgumentException("Already Exists");

                if (ChannelId.WILD.equals(next))
                    _wild=channel;
                else if (ChannelId.WILDWILD.equals(next))
                    _wildWild=channel;
                _bayeux.addChannel(channel);
            }
        }
        else
        {
            ChannelImpl branch=(ChannelImpl)_bayeux.getChannel((_id.depth() == 0?"/":(_id.toString() + "/")) + next,true);
            branch.addChild(channel);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filter
     */
    public void addDataFilter(DataFilter filter)
    {
        synchronized(this)
        {
            _dataFilters=(DataFilter[])LazyList.addToArray(_dataFilters,filter,null);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public ChannelId getChannelId()
    {
        return _id;
    }

    /* ------------------------------------------------------------ */
    public ChannelImpl getChild(ChannelId id)
    {
        String next=id.getSegment(_id.depth());
        if (next == null)
            return null;

        ChannelImpl channel=_children.get(next);

        if (channel == null || channel.getChannelId().depth() == id.depth())
        {
            return channel;
        }
        return channel.getChild(id);
    }

    /* ------------------------------------------------------------ */
    public void getChannels(List<Channel> list)
    {
        synchronized(this)
        {
            list.add(this);
            for (ChannelImpl channel : _children.values())
                channel.getChannels(list);
        }
    }

    /* ------------------------------------------------------------ */
    public int getChannelCount()
    {
        return _children.size();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public String getId()
    {
        return _id.toString();
    }

    /* ------------------------------------------------------------ */
    public boolean isPersistent()
    {
        return _persistent;
    }

    /* ------------------------------------------------------------ */
    public void deliver(Client from, Iterable<Client> to, Object data, String id)
    {
        MessageImpl message=_bayeux.newMessage();
        message.put(Bayeux.CHANNEL_FIELD,getId());
        message.put(Bayeux.DATA_FIELD,data);
        if (id != null)
            message.put(Bayeux.ID_FIELD,id);

        Message m=_bayeux.extendSendBayeux(from,message);

        if (m != null)
        {
            for (Client t : to)
                ((ClientImpl)t).doDelivery(from,m);
        }
        if (m instanceof MessageImpl)
            ((MessageImpl)m).decRef();
    }

    /* ------------------------------------------------------------ */
    public void publish(Client fromClient, Object data, String msgId)
    {
        _bayeux.doPublish(getChannelId(),fromClient,data,msgId,false);
    }

    /* ------------------------------------------------------------ */
    public void publishLazy(Client fromClient, Object data, String msgId)
    {
        _bayeux.doPublish(getChannelId(),fromClient,data,msgId,true);
    }

    /* ------------------------------------------------------------ */
    public boolean remove()
    {
        return _bayeux.removeChannel(this);
    }

    /* ------------------------------------------------------------ */
    public boolean doRemove(ChannelImpl channel, List<ChannelBayeuxListener> listeners)
    {
        ChannelId channelId=channel.getChannelId();
        int diff=channel._id.depth() - _id.depth();

        if (diff >= 1)
        {
            String key=channelId.getSegment(_id.depth());
            ChannelImpl child=_children.get(key);

            if (child != null)
            {
                // is it this child we are removing?
                if (diff == 1)
                {
                    if (!child.isPersistent())
                    {
                        // synchronize add and doRemove to avoid concurrent
                        // updates
                        synchronized(this)
                        {
                            if (child.getChannelCount() > 0)
                            {
                                // remove the children of the child
                                for (ChannelImpl c : child._children.values())
                                    child.doRemove(c,listeners);
                            }

                            // remove the child
                            _children.remove(key);
                        }
                        for (ChannelBayeuxListener l : listeners)
                            l.channelRemoved(channel);
                        return true;
                    }
                    return false;
                }

                boolean removed=child.doRemove(channel,listeners);
                if (removed && !child.isPersistent() && child.getChannelCount() == 0 && child.getSubscriberCount() == 0)
                {
                    // synchronize add and doRemove to avoid concurrent updates
                    synchronized(this)
                    {
                        _children.remove(key);
                    }
                    for (ChannelBayeuxListener l : listeners)
                        l.channelRemoved(channel);
                }

                return removed;
            }

        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filter
     */
    public DataFilter removeDataFilter(DataFilter filter)
    {
        synchronized(this)
        {
            _dataFilters=(DataFilter[])LazyList.removeFromArray(_dataFilters,filter);
            return filter;
        }
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param client
     */
    public void subscribe(Client client)
    {
        if (!(client instanceof ClientImpl))
            throw new IllegalArgumentException("Client instance not obtained from Bayeux.newClient()");

        synchronized(this)
        {
            for (ClientImpl c : _subscribers)
            {
                if (client.equals(c))
                    return;
            }
            _subscribers=(ClientImpl[])LazyList.addToArray(_subscribers,client,null);

            for (SubscriptionListener l : _subscriptionListeners)
                l.subscribed(client,this);
        }

        ((ClientImpl)client).addSubscription(this);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _id.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param client
     */
    public void unsubscribe(Client client)
    {
        if (!(client instanceof ClientImpl))
            throw new IllegalArgumentException("Client instance not obtained from Bayeux.newClient()");
        ((ClientImpl)client).removeSubscription(this);
        synchronized(this)
        {
            _subscribers=(ClientImpl[])LazyList.removeFromArray(_subscribers,client);

            for (SubscriptionListener l : _subscriptionListeners)
                l.unsubscribed(client,this);

            if (!_persistent && _subscribers.length == 0 && _children.size() == 0)
                remove();
        }
    }

    /* ------------------------------------------------------------ */
    protected void doDelivery(ChannelId to, Client from, Message msg)
    {
        int tail=to.depth() - _id.depth();

        Object data=msg.getData();

        // if we have data, filter it
        if (data != null)
        {
            Object old=data;

            try
            {
                switch(tail)
                {
                    case 0:
                    {
                        final DataFilter[] filters=_dataFilters;
                        for (DataFilter filter : filters)
                        {
                            data=filter.filter(from,this,data);
                            if (data == null)
                                return;
                        }
                    }
                        break;

                    case 1:
                        if (_wild != null)
                        {
                            final DataFilter[] filters=_wild._dataFilters;
                            for (DataFilter filter : filters)
                            {
                                data=filter.filter(from,this,data);
                                if (data == null)
                                    return;
                            }
                        }

                    default:
                        if (_wildWild != null)
                        {
                            final DataFilter[] filters=_wildWild._dataFilters;
                            for (DataFilter filter : filters)
                            {
                                data=filter.filter(from,this,data);
                                if (data == null)
                                    return;
                            }
                        }
                }
            }
            catch(IllegalStateException e)
            {
                Log.ignore(e);
                return;
            }

            // TODO this may not be correct if the message is reused.
            // probably should close message ?
            if (data != old)
                msg.put(AbstractBayeux.DATA_FIELD,data);
        }

        switch(tail)
        {
            case 0:
            {
                if (_lazy && msg instanceof MessageImpl)
                    ((MessageImpl)msg).setLazy(true);

                final ClientImpl[] subscribers=_subscribers;
                if (subscribers.length > 0)
                {
                    // fair delivery
                    int split=_split++ % _subscribers.length;
                    for (int i=split; i < subscribers.length; i++)
                        subscribers[i].doDelivery(from,msg);
                    for (int i=0; i < split; i++)
                        subscribers[i].doDelivery(from,msg);
                }
                break;
            }

            case 1:
                if (_wild != null)
                {
                    if (_wild._lazy && msg instanceof MessageImpl)
                        ((MessageImpl)msg).setLazy(true);
                    final ClientImpl[] subscribers=_wild._subscribers;
                    for (ClientImpl client : subscribers)
                        client.doDelivery(from,msg);
                }

            default:
            {
                if (_wildWild != null)
                {
                    if (_wildWild._lazy && msg instanceof MessageImpl)
                        ((MessageImpl)msg).setLazy(true);
                    final ClientImpl[] subscribers=_wildWild._subscribers;
                    for (ClientImpl client : subscribers)
                        client.doDelivery(from,msg);
                }
                String next=to.getSegment(_id.depth());
                ChannelImpl channel=_children.get(next);
                if (channel != null)
                    channel.doDelivery(to,from,msg);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public Collection<Client> getSubscribers()
    {
        synchronized(this)
        {
            return Arrays.asList((Client[])_subscribers);
        }
    }

    /* ------------------------------------------------------------ */
    public int getSubscriberCount()
    {
        synchronized(this)
        {
            return _subscribers.length;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see dojox.cometd.Channel#getFilters()
     */
    public Collection<DataFilter> getDataFilters()
    {
        synchronized(this)
        {
            return Arrays.asList(_dataFilters);
        }
    }

    /* ------------------------------------------------------------ */
    public void addListener(ChannelListener listener)
    {
        synchronized(this)
        {
            if (listener instanceof SubscriptionListener)
                _subscriptionListeners=(SubscriptionListener[])LazyList.addToArray(_subscriptionListeners,listener,null);
        }
    }

    public void removeListener(ChannelListener listener)
    {
        synchronized(this)
        {
            if (listener instanceof SubscriptionListener)
            {
                _subscriptionListeners=(SubscriptionListener[])LazyList.removeFromArray(_subscriptionListeners,listener);
            }
        }
    }
}
