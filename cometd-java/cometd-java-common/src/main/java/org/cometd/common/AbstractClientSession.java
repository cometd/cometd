/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.common;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.eclipse.jetty.util.AttributesMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Partial implementation of {@link ClientSession}.</p>
 * <p>It handles extensions and batching, and provides utility methods to be used by subclasses.</p>
 */
public abstract class AbstractClientSession implements ClientSession
{
    protected static final Logger logger = LoggerFactory.getLogger(ClientSession.class);
    private static final AtomicLong _idGen = new AtomicLong(0);
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, AbstractSessionChannel> _channels = new ConcurrentHashMap<String, AbstractSessionChannel>();
    private final AtomicInteger _batch = new AtomicInteger();

    protected AbstractClientSession()
    {
    }

    protected String newMessageId()
    {
        return String.valueOf(_idGen.incrementAndGet());
    }

    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    public List<Extension> getExtensions()
    {
        return Collections.unmodifiableList(_extensions);
    }

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

    protected abstract ChannelId newChannelId(String channelId);

    protected abstract AbstractSessionChannel newChannel(ChannelId channelId);

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

    protected ConcurrentMap<String, AbstractSessionChannel> getChannels()
    {
        return _channels;
    }

    public void startBatch()
    {
        _batch.incrementAndGet();
    }

    protected abstract void sendBatch();

    public boolean endBatch()
    {
        if (_batch.decrementAndGet()==0)
        {
            sendBatch();
            return true;
        }
        return false;
    }

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

    protected boolean isBatching()
    {
        return _batch.get() > 0;
    }

    public Object getAttribute(String name)
    {
        return _attributes.getAttribute(name);
    }

    public Set<String> getAttributeNames()
    {
        return _attributes.getAttributeNameSet();
    }

    public Object removeAttribute(String name)
    {
        Object old = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    public void setAttribute(String name, Object value)
    {
        _attributes.setAttribute(name,value);
    }

    protected void resetSubscriptions()
    {
        for (AbstractSessionChannel ch : _channels.values())
            ch.resetSubscriptions();
    }

    /**
     * <p>Receives a message (from the server) and process it.</p>
     * <p>Processing the message involves calling the receive {@link Extension extensions}
     * and the channel {@link ClientSessionChannel.ClientSessionChannelListener listeners}.</p>
     * @param message the message received.
     */
    public void receive(final Message.Mutable message)
    {
        String id = message.getChannel();
        if (id == null)
            throw new IllegalArgumentException("Bayeux messages must have a channel, " + message);

        if (!extendRcv(message))
            return;

        MarkableReference<AbstractSessionChannel> channelRef = getReleasableChannel(id);
        AbstractSessionChannel channel = channelRef.getReference();
        channel.notifyMessageListeners(message);
        if (channelRef.isMarked())
            channel.release();

        ChannelId channelId = channel.getChannelId();
        for (String wildChannelName : channelId.getWilds())
        {
            MarkableReference<AbstractSessionChannel> wildChannelRef = getReleasableChannel(wildChannelName);
            AbstractSessionChannel wildChannel = wildChannelRef.getReference();
            wildChannel.notifyMessageListeners(message);
            if (wildChannelRef.isMarked())
                wildChannel.release();
        }
    }

    private MarkableReference<AbstractSessionChannel> getReleasableChannel(String id)
    {
        // Use getChannels().get(channelName) instead of getChannel(channelName)
        // to avoid to cache channels that can be released immediately.

        AbstractSessionChannel channel = ChannelId.isMeta(id) ? (AbstractSessionChannel)getChannel(id) : getChannels().get(id);
        if (channel != null)
            return new MarkableReference<AbstractSessionChannel>(channel, false);
        return new MarkableReference<AbstractSessionChannel>(newChannel(newChannelId(id)), true);
    }

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

    /**
     * <p>A channel scoped to a {@link ClientSession}.</p>
     */
    protected abstract class AbstractSessionChannel implements ClientSessionChannel
    {
        private final ChannelId _id;
        private final AttributesMap _attributes = new AttributesMap();
        private final CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<MessageListener>();
        private final AtomicInteger _subscriptionCount = new AtomicInteger();
        private final CopyOnWriteArrayList<ClientSessionChannelListener> _listeners = new CopyOnWriteArrayList<ClientSessionChannelListener>();
        private volatile boolean _released;

        protected AbstractSessionChannel(ChannelId id)
        {
            _id=id;
        }

        public ChannelId getChannelId()
        {
            return _id;
        }

        public void addListener(ClientSessionChannelListener listener)
        {
            throwIfReleased();
            _listeners.add(listener);
        }

        public void removeListener(ClientSessionChannelListener listener)
        {
            throwIfReleased();
            _listeners.remove(listener);
        }

        public List<ClientSessionChannelListener> getListeners()
        {
            return Collections.unmodifiableList(_listeners);
        }

        protected abstract void sendSubscribe();

        protected abstract void sendUnSubscribe();

        public void subscribe(MessageListener listener)
        {
            throwIfReleased();
            boolean added = _subscriptions.add(listener);
            if (added)
            {
                int count = _subscriptionCount.incrementAndGet();
                if (count == 1)
                    sendSubscribe();
            }
        }

        public void unsubscribe(MessageListener listener)
        {
            throwIfReleased();
            boolean removed = _subscriptions.remove(listener);
            if (removed)
            {
                int count = _subscriptionCount.decrementAndGet();
                if (count == 0)
                    sendUnSubscribe();
            }
        }

        public void unsubscribe()
        {
            throwIfReleased();
            for (MessageListener listener : _subscriptions)
                unsubscribe(listener);
        }

        public List<MessageListener> getSubscribers()
        {
            return Collections.unmodifiableList(_subscriptions);
        }

        public boolean release()
        {
            if (_released)
                return false;

            if (_subscriptions.isEmpty() && _listeners.isEmpty())
            {
                boolean removed = _channels.remove(getId(), this);
                _released = removed;
                return removed;
            }
            return false;
        }

        public boolean isReleased()
        {
            return _released;
        }

        protected void resetSubscriptions()
        {
            throwIfReleased();
            for (MessageListener l : _subscriptions)
            {
                if (_subscriptions.remove(l))
                    _subscriptionCount.decrementAndGet();
            }
        }

        public String getId()
        {
            return _id.toString();
        }

        public boolean isDeepWild()
        {
            return _id.isDeepWild();
        }

        public boolean isMeta()
        {
            return _id.isMeta();
        }

        public boolean isService()
        {
            return _id.isService();
        }

        public boolean isBroadcast()
        {
            return !isMeta() && !isService();
        }

        public boolean isWild()
        {
            return _id.isWild();
        }

        protected void notifyMessageListeners(Message message)
        {
            throwIfReleased();
            for (ClientSessionChannelListener listener : _listeners)
            {
                if (listener instanceof ClientSessionChannel.MessageListener)
                    notifyOnMessage((MessageListener)listener, message);
            }
            for (ClientSessionChannelListener listener : _subscriptions)
            {
                if (listener instanceof ClientSessionChannel.MessageListener)
                {
                    if (message.getData() != null)
                        notifyOnMessage((MessageListener)listener, message);
                }
            }
        }

        private void notifyOnMessage(MessageListener listener, Message message)
        {
            throwIfReleased();
            try
            {
                listener.onMessage(this, message);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }

        public void setAttribute(String name, Object value)
        {
            throwIfReleased();
            _attributes.setAttribute(name, value);
        }

        public Object getAttribute(String name)
        {
            throwIfReleased();
            return _attributes.getAttribute(name);
        }

        public Set<String> getAttributeNames()
        {
            throwIfReleased();
            return _attributes.keySet();
        }

        public Object removeAttribute(String name)
        {
            throwIfReleased();
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

        protected void throwIfReleased()
        {
            if (isReleased())
                throw new IllegalStateException("Channel " + this + " has been released");
        }

        @Override
        public String toString()
        {
            return _id.toString();
        }
    }

    /**
     * Non-volatile, non-atomic version of {@link AtomicMarkableReference}.
     * @param <T> the reference type
     */
    private static class MarkableReference<T>
    {
        private final T reference;
        private final boolean mark;

        private MarkableReference(T reference, boolean mark)
        {
            this.reference = reference;
            this.mark = mark;
        }

        public T getReference()
        {
            return reference;
        }

        public boolean isMarked()
        {
            return mark;
        }
    }
}
