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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;
import org.cometd.ChannelListener;
import org.cometd.Client;
import org.cometd.DataFilter;
import org.cometd.Message;
import org.cometd.SubscriptionListener;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 * A Bayeux Channel
 *
 * @version $Revision: 1035 $ $Date: 2010-03-22 11:59:52 +0100 (Mon, 22 Mar 2010) $
 */
public class ChannelImpl implements Channel
{
    private final AbstractBayeux _bayeux;
    private final ChannelId _id;
    private final ConcurrentHashMap<String,ChannelImpl> _children=new ConcurrentHashMap<String,ChannelImpl>();
    private final List<ClientImpl> _subscribers=new CopyOnWriteArrayList<ClientImpl>();
    private final List<DataFilter> _dataFilters=new CopyOnWriteArrayList<DataFilter>();
    private final List<SubscriptionListener> _subscriptionListeners=new CopyOnWriteArrayList<SubscriptionListener>();
    private final CountDownLatch _initialized = new CountDownLatch(1);
    private volatile ChannelImpl _wild;
    private volatile ChannelImpl _wildWild;
    private volatile boolean _persistent;
    private volatile int _split;
    private volatile boolean _lazy;

    /* ------------------------------------------------------------ */
    protected ChannelImpl(String id, AbstractBayeux bayeux)
    {
        _id=new ChannelId(id);
        _bayeux=bayeux;
    }

    /* ------------------------------------------------------------ */
    /**
     * Wait for the channel to be initialized, at most for bayeux maxInterval.
     * Channel initialization means waiting for {@link #addChild(ChannelImpl)}
     * to finish calling {@link AbstractBayeux#addChannel(ChannelImpl)},
     * which notifies channel listeners. Channel initialization may therefore
     * be delayed in case of slow listeners, but it is guaranteed that any
     * concurrent channel creation will wait until listeners have been called,
     * therefore enforcing channel creation + notification to be atomic.
     */
    private void waitForInitialized()
    {
        try
        {
            if (!_initialized.await(_bayeux.getMaxInterval(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Not Initialized: " + this);
        }
        catch (InterruptedException x)
        {
            throw new IllegalStateException("Initialization interrupted: " + this, x);
        }
    }

    /* ------------------------------------------------------------ */
    private void initialized()
    {
        _initialized.countDown();
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
    /**
     * Adds a channel
     * @param channel the child channel to add
     * @return The added channel, or the existing channel if another thread
     * already added the channel
     */
    public ChannelImpl addChild(ChannelImpl channel)
    {
        ChannelId childId=channel.getChannelId();
        if (!_id.isParentOf(childId))
            throw new IllegalArgumentException(_id + " not parent of " + childId);

        String next=childId.getSegment(_id.depth());

        if ((childId.depth() - _id.depth()) == 1)
        {
            // add the channel to this channels
            ChannelImpl old=_children.putIfAbsent(next,channel);
            if (old != null)
            {
                old.waitForInitialized();
                return old;
            }

            if (ChannelId.WILD.equals(next))
                _wild=channel;
            else if (ChannelId.WILDWILD.equals(next))
                _wildWild=channel;

            _bayeux.initChannel(channel);
            channel.initialized();

            _bayeux.addChannel(channel);
            return channel;
        }
        else
        {
            ChannelImpl branch=(ChannelImpl)_bayeux.getChannel((_id.depth() == 0?"/":(_id.toString() + "/")) + next,true);
            return branch.addChild(channel);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filter the data filter to add
     */
    public void addDataFilter(DataFilter filter)
    {
        _dataFilters.add(filter);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the ChannelId of this channel
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
        if (channel!=null)
            channel.waitForInitialized();

        if (channel == null || channel.getChannelId().depth() == id.depth())
        {
            return channel;
        }
        return channel.getChild(id);
    }

    /* ------------------------------------------------------------ */
    public void getChannels(List<Channel> list)
    {
        list.add(this);
        for (ChannelImpl channel : _children.values())
            channel.getChannels(list);
    }

    /* ------------------------------------------------------------ */
    public int getChannelCount()
    {
        return _children.size();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the id of this channel in string form
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
                deliverToSubscriber((ClientImpl)t,from,m);
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
                        // remove the child
                        child=_children.remove(key);
                        if (child !=null)
                        {
                            if (_wild==channel)
                                _wild=null;
                            else if (_wildWild==channel)
                                _wildWild=null;
                            if ( child.getChannelCount() > 0)
                            {
                                // remove the children of the child
                                for (ChannelImpl c : child._children.values())
                                    child.doRemove(c,listeners);
                            }
                            notifyChannelRemoved(listeners, child);
                        }
                        return true;
                    }
                    return false;
                }

                boolean removed=child.doRemove(channel,listeners);

                // Do we remove a non persistent child?
                if (removed && !child.isPersistent() && child.getChannelCount() == 0 && child.getSubscriberCount() == 0)
                {
                    child=_children.remove(key);
                    if (child!=null)
                        notifyChannelRemoved(listeners, child);
                }

                return removed;
            }

        }
        return false;
    }

    private void notifyChannelRemoved(List<ChannelBayeuxListener> listeners, ChannelImpl channel)
    {
        for (ChannelBayeuxListener listener : listeners)
        {
            try
            {
                listener.channelRemoved(channel);
            }
            catch (RuntimeException x)
            {
                AbstractBayeux.logger.info("Unexpected exception while invoking listener " + listener, x);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filter the data filter to remove
     */
    public DataFilter removeDataFilter(DataFilter filter)
    {
        _dataFilters.remove(filter);
        return filter;
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param client the client to subscribe to this channel
     */
    public void subscribe(Client client)
    {
        if (!(client instanceof ClientImpl))
            throw new IllegalArgumentException("Client instance not obtained from Bayeux.newClient()");

        for (ClientImpl c : _subscribers)
        {
            if (client.equals(c))
                return;
        }

        _subscribers.add((ClientImpl)client);

        for (SubscriptionListener l : _subscriptionListeners)
            l.subscribed(client,this);

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
     * @param c the client to unsubscribe from this channel
     */
    public void unsubscribe(Client c)
    {
        if (!(c instanceof ClientImpl))
            throw new IllegalArgumentException("Client instance not obtained from Bayeux.newClient()");
        ClientImpl client = (ClientImpl)c;

        client.removeSubscription(this);

        _subscribers.remove(client);

        for (SubscriptionListener l : _subscriptionListeners)
            l.unsubscribed(client,this);

        if (!_persistent && _subscribers.size() == 0 && _children.size() == 0)
            remove();
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
                        for (DataFilter filter : _dataFilters)
                        {
                            data=filter.filter(from,this,data);
                            if (data == null)
                                return;
                        }
                    }
                        break;

                    case 1:
                        final ChannelImpl wild = _wild;
                        if (wild != null)
                        {
                            for (DataFilter filter : wild._dataFilters)
                            {
                                data=filter.filter(from,this,data);
                                if (data == null)
                                    return;
                            }
                        }

                    default:
                        final ChannelImpl wildWild = _wildWild;
                        if (wildWild != null)
                        {
                            for (DataFilter filter : wildWild._dataFilters)
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

                final ClientImpl[] subscribers=_subscribers.toArray(new ClientImpl[_subscribers.size()]);
                if (subscribers.length > 0)
                {
                    // fair delivery
                    int split=_split++ % subscribers.length;
                    for (int i=split; i < subscribers.length; i++)
                        deliverToSubscriber(subscribers[i],from,msg);
                    for (int i=0; i < split; i++)
                        deliverToSubscriber(subscribers[i],from,msg);
                }
                break;
            }

            case 1:
                final ChannelImpl wild = _wild;
                if (wild != null)
                {
                    if (wild._lazy && msg instanceof MessageImpl)
                        ((MessageImpl)msg).setLazy(true);
                    for (ClientImpl client : wild._subscribers)
                        wild.deliverToSubscriber(client,from,msg);
                }

            default:
            {
                final ChannelImpl wildWild = _wildWild;
                if (wildWild != null)
                {
                    if (wildWild._lazy && msg instanceof MessageImpl)
                        ((MessageImpl)msg).setLazy(true);
                    for (ClientImpl client : wildWild._subscribers)
                        wildWild.deliverToSubscriber(client,from,msg);
                }
                String next=to.getSegment(_id.depth());
                ChannelImpl channel=_children.get(next);
                if (channel != null)
                    channel.doDelivery(to,from,msg);
            }
        }
    }

    private void deliverToSubscriber(ClientImpl subscriber, Client from, Message message)
    {
        if (_bayeux.hasClient(subscriber.getId()))
            subscriber.doDelivery(from, message);
        else
            unsubscribe(subscriber);
    }

    /* ------------------------------------------------------------ */
    public Collection<Client> getSubscribers()
    {
        return new ArrayList<Client>(_subscribers);
    }

    /* ------------------------------------------------------------ */
    public int getSubscriberCount()
    {
        return _subscribers.size();
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see dojox.cometd.Channel#getFilters()
     */
    public Collection<DataFilter> getDataFilters()
    {
        return new ArrayList<DataFilter>(_dataFilters);
    }

    /* ------------------------------------------------------------ */
    public void addListener(ChannelListener listener)
    {
        if (listener instanceof SubscriptionListener)
        {
            _subscriptionListeners.add((SubscriptionListener)listener);
        }
    }

    public void removeListener(ChannelListener listener)
    {
        if (listener instanceof SubscriptionListener)
        {
            _subscriptionListeners.remove((SubscriptionListener)listener);
        }
    }
}
