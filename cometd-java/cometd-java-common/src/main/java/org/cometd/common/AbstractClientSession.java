package org.cometd.common;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>Partial implementation of {@link ClientSession}.</p>
 * <p>It handles extensions and batching, and provides utility methods to be used by subclasses.</p>
 */
public abstract class AbstractClientSession implements ClientSession
{
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, AbstractSessionChannel> _channels = new ConcurrentHashMap<String, AbstractSessionChannel>();
    private final AtomicInteger _batch = new AtomicInteger();
    private final AtomicInteger _idGen = new AtomicInteger(0);

    /* ------------------------------------------------------------ */
    protected AbstractClientSession()
    {
    }

    /* ------------------------------------------------------------ */
    protected String newMessageId()
    {
        return String.valueOf(_idGen.incrementAndGet());
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    /* ------------------------------------------------------------ */
    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSend(Message.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if (!extension.sendMeta(this, message))
                    return false;
        }
        else
        {
            for (Extension extension : _extensions)
                if (!extension.send(this, message))
                    return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendRcv(Message.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if (!extension.rcvMeta(this, message))
                    return false;
        }
        else
        {
            for (Extension extension : _extensions)
                if (!extension.rcv(this, message))
                    return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected abstract ChannelId newChannelId(String channelId);

    /* ------------------------------------------------------------ */
    protected abstract AbstractSessionChannel newChannel(ChannelId channelId);

    /* ------------------------------------------------------------ */
    public ClientSessionChannel getChannel(String channelId)
    {
        AbstractSessionChannel channel = _channels.get(channelId);
        if (channel==null)
        {
            ChannelId id = newChannelId(channelId);
            AbstractSessionChannel new_channel=newChannel(id);
            channel=_channels.putIfAbsent(channelId,new_channel);
            if (channel==null)
                channel=new_channel;
        }
        return channel;
    }
    
    /* ------------------------------------------------------------ */
    protected ConcurrentMap<String, AbstractSessionChannel> getChannels()
    {
        return _channels;
    }

    /* ------------------------------------------------------------ */
    public void startBatch()
    {
        _batch.incrementAndGet();
    }

    /* ------------------------------------------------------------ */
    protected abstract void sendBatch();

    /* ------------------------------------------------------------ */
    public boolean endBatch()
    {
        if (_batch.decrementAndGet()==0)
        {
            sendBatch();
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    protected boolean isBatching()
    {
        return _batch.get() > 0;
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
        Object old = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    /* ------------------------------------------------------------ */
    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }
    
    /* ------------------------------------------------------------ */
    protected void resetSubscriptions()
    {
        for (AbstractSessionChannel ch : _channels.values())
            ch.resetSubscriptions();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * <p>Receives a message (from the server) and process it.</p>
     * <p>Processing the message involves calling the receive {@link Extension extensions}
     * and the channel {@link ClientSessionChannel.ClientSessionChannelListener listeners}.</p>
     * @param message the message received.
     * @param mutable the mutable version of the message received
     */
    public void receive(final Message.Mutable message)
    {
        String id = message.getChannel();
        if (id == null)
            throw new IllegalArgumentException("Bayeux messages must have a channel, " + message);

        if (!extendRcv(message))
            return;

        AbstractSessionChannel channel = (AbstractSessionChannel)getChannel(id);
        ChannelId channelId = channel.getChannelId();

        channel.notifyMessageListeners(message);

        for (String channelPattern : channelId.getWilds())
        {
            ChannelId channelIdPattern = newChannelId(channelPattern);
            if (channelIdPattern.matches(channelId))
            {
                AbstractSessionChannel wildChannel = (AbstractSessionChannel)getChannel(channelPattern);
                wildChannel.notifyMessageListeners(message);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append('\n');

        int leaves=_channels.size();
        int i=0;
        for (AbstractSessionChannel child : _channels.values())
        {
            b.append(indent);
            b.append(" +-");
            child.dump(b,indent+((++i==leaves)?"   ":" | "));
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * <p>A channel scoped to a {@link ClientSession}.</p>
     */
    protected abstract static class AbstractSessionChannel implements ClientSessionChannel
    {
        protected final Logger logger = Log.getLogger(getClass().getName());
        private final ChannelId _id;
        private final AttributesMap _attributes = new AttributesMap();
        private final CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<MessageListener>();
        private final AtomicInteger _subscriptionCount = new AtomicInteger();
        private final CopyOnWriteArrayList<ClientSessionChannelListener> _listeners = new CopyOnWriteArrayList<ClientSessionChannelListener>();

        /* ------------------------------------------------------------ */
        protected AbstractSessionChannel(ChannelId id)
        {
            _id=id;
        }

        /* ------------------------------------------------------------ */
        public ChannelId getChannelId()
        {
            return _id;
        }

        /* ------------------------------------------------------------ */
        public void addListener(ClientSessionChannelListener listener)
        {
            _listeners.add(listener);
        }

        /* ------------------------------------------------------------ */
        public void removeListener(ClientSessionChannelListener listener)
        {
            _listeners.remove(listener);
        }

        /* ------------------------------------------------------------ */
        protected abstract void sendSubscribe();

        /* ------------------------------------------------------------ */
        protected abstract void sendUnSubscribe();

        /* ------------------------------------------------------------ */
        public void subscribe(MessageListener listener)
        {
            boolean added = _subscriptions.add(listener);
            if (added)
            {
                int count = _subscriptionCount.incrementAndGet();
                if (count == 1)
                    sendSubscribe();
            }
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe(MessageListener listener)
        {
            boolean removed = _subscriptions.remove(listener);
            if (removed)
            {
                int count = _subscriptionCount.decrementAndGet();
                if (count == 0)
                    sendUnSubscribe();
            }
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe()
        {
            for (MessageListener listener : _subscriptions)
                unsubscribe(listener);
        }

        /* ------------------------------------------------------------ */
        protected void resetSubscriptions()
        {
            for (MessageListener l : _subscriptions)
            {
                if (_subscriptions.remove(l))
                    _subscriptionCount.decrementAndGet();
            }
        }

        /* ------------------------------------------------------------ */
        public String getId()
        {
            return _id.toString();
        }

        /* ------------------------------------------------------------ */
        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        /* ------------------------------------------------------------ */
        public boolean isMeta()
        {
            return _id.isMeta();
        }

        /* ------------------------------------------------------------ */
        public boolean isService()
        {
            return _id.isService();
        }

        /* ------------------------------------------------------------ */
        public boolean isWild()
        {
            return _id.isWild();
        }

        protected void notifyMessageListeners(Message message)
        {
            for (ClientSessionChannelListener listener : _listeners)
            {
                if (listener instanceof ClientSessionChannel.MessageListener)
                {
                    try
                    {
                        ((MessageListener)listener).onMessage(this, message);
                    }
                    catch (Exception x)
                    {
                        logger.info(x);
                    }
                }
            }
            for (ClientSessionChannelListener listener : _subscriptions)
            {
                if (listener instanceof ClientSessionChannel.MessageListener)
                {
                    if (message.getData() != null)
                    {
                        try
                        {
                            ((MessageListener)listener).onMessage(this, message);
                        }
                        catch (Exception x)
                        {
                            logger.info(x);
                        }
                    }
                }
            }
        }

        public void setAttribute(String name, Object value)
        {
            _attributes.setAttribute(name, value);
        }

        public Object getAttribute(String name)
        {
            return _attributes.getAttribute(name);
        }

        public Set<String> getAttributeNames()
        {
            return _attributes.keySet();
        }

        public Object removeAttribute(String name)
        {
            Object old = getAttribute(name);
            _attributes.removeAttribute(name);
            return old;
        }

        protected void dump(StringBuilder b,String indent)
        {
            b.append(toString());
            b.append('\n');

            for (ClientSessionChannelListener child : _listeners)
            {
                b.append(indent);
                b.append(" +-");
                b.append(child);
                b.append('\n');
            }
            for (MessageListener child : _subscriptions)
            {
                b.append(indent);
                b.append(" +-");
                b.append(child);
                b.append('\n');
            }
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return _id.toString();
        }
    }
}
