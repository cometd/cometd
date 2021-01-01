/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Partial implementation of {@link ClientSession}.</p>
 * <p>It handles extensions and batching, and provides utility methods to be used by subclasses.</p>
 */
public abstract class AbstractClientSession implements ClientSession, Dumpable {
    private static final Logger logger = LoggerFactory.getLogger(ClientSession.class);
    private static final AtomicLong _idGen = new AtomicLong(0);

    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final AttributesMap _attributes = new AttributesMap();
    private final ConcurrentMap<String, AbstractSessionChannel> _channels = new ConcurrentHashMap<>();
    private final Map<String, ClientSessionChannel.MessageListener> _callbacks = new ConcurrentHashMap<>();
    private final Map<String, ClientSessionChannel.MessageListener> _subscribers = new ConcurrentHashMap<>();
    private final Map<String, MessageListener> _remoteCalls = new ConcurrentHashMap<>();
    private final AtomicInteger _batch = new AtomicInteger();

    protected AbstractClientSession() {
    }

    protected String newMessageId() {
        return String.valueOf(_idGen.incrementAndGet());
    }

    @Override
    public void addExtension(Extension extension) {
        _extensions.add(extension);
    }

    @Override
    public void removeExtension(Extension extension) {
        _extensions.remove(extension);
    }

    @Override
    public List<Extension> getExtensions() {
        return Collections.unmodifiableList(_extensions);
    }

    protected boolean extendSend(Message.Mutable message) {
        String messageId = message.getId();
        ListIterator<Extension> i = _extensions.listIterator();
        while (i.hasNext()) {
            i.next();
        }
        while (i.hasPrevious()) {
            Extension extension = i.previous();
            boolean proceed = message.isMeta() ?
                    extension.sendMeta(this, message) :
                    extension.send(this, message);
            if (!proceed) {
                unregisterSubscriber(messageId);
                unregisterCallback(messageId);
                return false;
            }
        }
        return true;
    }

    protected boolean extendRcv(Message.Mutable message) {
        String messageId = message.getId();
        for (Extension extension : _extensions) {
            boolean proceed = message.isMeta() ?
                    extension.rcvMeta(this, message) :
                    extension.rcv(this, message);
            if (!proceed) {
                unregisterCallback(messageId);
                return false;
            }
        }
        return true;
    }

    protected abstract ChannelId newChannelId(String channelId);

    protected abstract AbstractSessionChannel newChannel(ChannelId channelId);

    @Override
    public ClientSessionChannel getChannel(String channelName) {
        return getChannel(channelName, null);
    }

    public ClientSessionChannel getChannel(ChannelId channelId) {
        return getChannel(channelId.getId(), channelId);
    }

    private ClientSessionChannel getChannel(String channelName, ChannelId channelId) {
        AbstractSessionChannel channel = _channels.get(channelName);
        if (channel != null) {
            return channel;
        }
        if (channelId == null) {
            // Creating the ChannelId will also normalize the channelName.
            channelId = newChannelId(channelName);
            return getChannel(channelId);
        }
        AbstractSessionChannel newChannel = newChannel(channelId);
        channel = _channels.putIfAbsent(channelId.getId(), newChannel);
        if (channel == null) {
            channel = newChannel;
        }
        return channel;
    }

    protected ConcurrentMap<String, AbstractSessionChannel> getChannels() {
        return _channels;
    }

    @Override
    public void startBatch() {
        _batch.incrementAndGet();
    }

    protected abstract void sendBatch();

    @Override
    public boolean endBatch() {
        if (_batch.decrementAndGet() == 0) {
            sendBatch();
            return true;
        }
        return false;
    }

    @Override
    public void batch(Runnable batch) {
        startBatch();
        try {
            batch.run();
        } finally {
            endBatch();
        }
    }

    protected boolean isBatching() {
        return _batch.get() > 0;
    }

    @Override
    public Object getAttribute(String name) {
        return _attributes.getAttribute(name);
    }

    @Override
    public Set<String> getAttributeNames() {
        return _attributes.getAttributeNameSet();
    }

    @Override
    public Object removeAttribute(String name) {
        Object old = _attributes.getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    @Override
    public void setAttribute(String name, Object value) {
        _attributes.setAttribute(name, value);
    }

    @Override
    public void remoteCall(String target, Object data, MessageListener callback) {
        if (!target.startsWith("/")) {
            target = "/" + target;
        }
        String channelName = "/service" + target;
        Message.Mutable message = newMessage();
        String messageId = newMessageId();
        message.setId(messageId);
        message.setChannel(channelName);
        message.setData(data);
        _remoteCalls.put(messageId, callback);
        send(message);
    }

    protected abstract void send(Message.Mutable message);

    protected Message.Mutable newMessage() {
        return new HashMapMessage();
    }

    protected void resetSubscriptions() {
        for (AbstractSessionChannel ch : _channels.values()) {
            ch.resetSubscriptions();
        }
    }

    /**
     * <p>Receives a message (from the server) and process it.</p>
     * <p>Processing the message involves calling the receive {@link Extension extensions}
     * and the channel {@link ClientSessionChannel.ClientSessionChannelListener listeners}.</p>
     *
     * @param message the message received.
     */
    public void receive(final Message.Mutable message) {
        String channelName = message.getChannel();
        if (channelName == null) {
            throw new IllegalArgumentException("Bayeux message must have a channel: " + message);
        }

        if (Channel.META_SUBSCRIBE.equals(channelName)) {
            // Remove the subscriber if the subscription fails.
            ClientSessionChannel.MessageListener subscriber = unregisterSubscriber(message.getId());
            if (!message.isSuccessful()) {
                String subscription = (String)message.get(Message.SUBSCRIPTION_FIELD);
                MarkedReference<AbstractSessionChannel> channelRef = getReleasableChannel(subscription);
                AbstractSessionChannel channel = channelRef.getReference();
                channel.removeSubscription(subscriber);
                if (channelRef.isMarked()) {
                    channel.release();
                }
            }
        }

        if (!extendRcv(message)) {
            return;
        }

        if (!message.isPublishReply() || !message.isSuccessful()) {
            if (handleRemoteCall(message)) {
                return;
            }
        }

        notifyListeners(message);
    }

    private boolean handleRemoteCall(Message.Mutable message) {
        String messageId = message.getId();
        if (messageId != null) {
            MessageListener listener = _remoteCalls.remove(messageId);
            if (listener != null) {
                notifyMessageListener(listener, message);
                return true;
            }
        }
        return false;
    }

    private void notifyMessageListener(MessageListener listener, Message.Mutable message) {
        try {
            listener.onMessage(message);
        } catch (Throwable x) {
            logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected void notifyListeners(Message.Mutable message) {
        if (message.isMeta() || message.isPublishReply()) {
            String messageId = message.getId();
            ClientSessionChannel.MessageListener callback = unregisterCallback(messageId);
            if (callback != null) {
                notifyListener(callback, message);
            }
        }

        MarkedReference<AbstractSessionChannel> channelRef = getReleasableChannel(message.getChannel());
        AbstractSessionChannel channel = channelRef.getReference();
        channel.notifyMessageListeners(message);
        if (channelRef.isMarked()) {
            channel.release();
        }

        ChannelId channelId = channel.getChannelId();
        for (String wildChannelName : channelId.getWilds()) {
            MarkedReference<AbstractSessionChannel> wildChannelRef = getReleasableChannel(wildChannelName);
            AbstractSessionChannel wildChannel = wildChannelRef.getReference();
            wildChannel.notifyMessageListeners(message);
            if (wildChannelRef.isMarked()) {
                wildChannel.release();
            }
        }
    }

    protected void notifyListener(ClientSessionChannel.MessageListener listener, Message.Mutable message) {
        MarkedReference<AbstractSessionChannel> channelRef = getReleasableChannel(message.getChannel());
        AbstractSessionChannel channel = channelRef.getReference();
        channel.notifyOnMessage(listener, message);
        if (channelRef.isMarked()) {
            channel.release();
        }
    }

    private MarkedReference<AbstractSessionChannel> getReleasableChannel(String id) {
        // Use getChannels().get(channelName) instead of getChannel(channelName)
        // to avoid to cache channels that can be released immediately.

        AbstractSessionChannel channel = ChannelId.isMeta(id) ? (AbstractSessionChannel)getChannel(id) : getChannels().get(id);
        if (channel != null) {
            return new MarkedReference<>(channel, false);
        }
        return new MarkedReference<>(newChannel(newChannelId(id)), true);
    }

    protected void registerCallback(String messageId, ClientSessionChannel.MessageListener callback) {
        if (callback != null) {
            _callbacks.put(messageId, callback);
        }
    }

    protected ClientSessionChannel.MessageListener unregisterCallback(String messageId) {
        if (messageId == null) {
            return null;
        }
        return _callbacks.remove(messageId);
    }

    protected void registerSubscriber(String messageId, ClientSessionChannel.MessageListener subscriber) {
        if (subscriber != null) {
            _subscribers.put(messageId, subscriber);
        }
    }

    protected ClientSessionChannel.MessageListener unregisterSubscriber(String messageId) {
        if (messageId == null) {
            return null;
        }
        return _subscribers.remove(messageId);
    }

    @Override
    public String dump() {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        ContainerLifeCycle.dumpObject(out, this);

        List<Dumpable> children = new ArrayList<>();

        children.add(new Dumpable() {
            @Override
            public String dump() {
                return null;
            }

            @Override
            public void dump(Appendable out, String indent) throws IOException {
                Collection<AbstractSessionChannel> channels = getChannels().values();
                ContainerLifeCycle.dumpObject(out, "channels: " + channels.size());
                ContainerLifeCycle.dump(out, indent, channels);
            }
        });

        ContainerLifeCycle.dump(out, indent, children);
    }

    /**
     * <p>A channel scoped to a {@link ClientSession}.</p>
     */
    protected abstract class AbstractSessionChannel implements ClientSessionChannel, Dumpable {
        private final ChannelId _id;
        private final AttributesMap _attributes = new AttributesMap();
        private final CopyOnWriteArrayList<MessageListener> _subscriptions = new CopyOnWriteArrayList<>();
        private final AtomicInteger _subscriptionCount = new AtomicInteger();
        private final CopyOnWriteArrayList<ClientSessionChannelListener> _listeners = new CopyOnWriteArrayList<>();
        private volatile boolean _released;

        protected AbstractSessionChannel(ChannelId id) {
            _id = id;
        }

        @Override
        public ChannelId getChannelId() {
            return _id;
        }

        @Override
        public void addListener(ClientSessionChannelListener listener) {
            throwIfReleased();
            _listeners.add(listener);
        }

        @Override
        public void removeListener(ClientSessionChannelListener listener) {
            throwIfReleased();
            _listeners.remove(listener);
        }

        @Override
        public List<ClientSessionChannelListener> getListeners() {
            return Collections.unmodifiableList(_listeners);
        }

        @Override
        public void publish(Object data) {
            publish(data, null);
        }

        @Override
        public void publish(Object data, MessageListener callback) {
            if (data instanceof Message.Mutable) {
                publish((Message.Mutable)data, callback);
            } else {
                Message.Mutable message = newMessage();
                message.setData(data);
                publish(message, callback);
            }
        }

        @Override
        public void publish(Message.Mutable message, MessageListener callback) {
            throwIfReleased();
            String messageId = newMessageId();
            message.setId(messageId);
            message.setChannel(getId());
            registerCallback(messageId, callback);
            send(message);
        }

        @Override
        public void subscribe(MessageListener listener) {
            subscribe(listener, null);
        }

        @Override
        public void subscribe(MessageListener listener, MessageListener callback) {
            throwIfReleased();
            boolean added = _subscriptions.add(listener);
            if (added) {
                int count = _subscriptionCount.incrementAndGet();
                if (count == 1) {
                    sendSubscribe(listener, callback);
                }
            }
        }

        protected void sendSubscribe(MessageListener listener, MessageListener callback) {
            Message.Mutable message = newMessage();
            String messageId = newMessageId();
            message.setId(messageId);
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            registerSubscriber(messageId, listener);
            registerCallback(messageId, callback);
            send(message);
        }

        @Override
        public void unsubscribe(MessageListener listener) {
            unsubscribe(listener, null);
        }

        @Override
        public void unsubscribe(MessageListener listener, MessageListener callback) {
            boolean removedLast = removeSubscription(listener);
            if (removedLast) {
                sendUnSubscribe(callback);
            }
        }

        private boolean removeSubscription(MessageListener listener) {
            throwIfReleased();
            boolean removed = _subscriptions.remove(listener);
            if (removed) {
                return _subscriptionCount.decrementAndGet() == 0;
            }
            return false;
        }

        protected void sendUnSubscribe(MessageListener callback) {
            Message.Mutable message = newMessage();
            String messageId = newMessageId();
            message.setId(messageId);
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            registerCallback(messageId, callback);
            send(message);
        }

        @Override
        public void unsubscribe() {
            throwIfReleased();
            for (MessageListener listener : _subscriptions) {
                unsubscribe(listener);
            }
        }

        @Override
        public List<MessageListener> getSubscribers() {
            return Collections.unmodifiableList(_subscriptions);
        }

        @Override
        public boolean release() {
            if (_released) {
                return false;
            }

            if (_subscriptions.isEmpty() && _listeners.isEmpty()) {
                boolean removed = _channels.remove(getId(), this);
                _released = removed;
                return removed;
            }
            return false;
        }

        @Override
        public boolean isReleased() {
            return _released;
        }

        protected void resetSubscriptions() {
            throwIfReleased();
            for (MessageListener l : _subscriptions) {
                if (_subscriptions.remove(l)) {
                    _subscriptionCount.decrementAndGet();
                }
            }
        }

        @Override
        public String getId() {
            return _id.getId();
        }

        @Override
        public boolean isDeepWild() {
            return _id.isDeepWild();
        }

        @Override
        public boolean isMeta() {
            return _id.isMeta();
        }

        @Override
        public boolean isService() {
            return _id.isService();
        }

        @Override
        public boolean isBroadcast() {
            return !isMeta() && !isService();
        }

        @Override
        public boolean isWild() {
            return _id.isWild();
        }

        protected void notifyMessageListeners(Message message) {
            throwIfReleased();
            for (ClientSessionChannelListener listener : _listeners) {
                if (listener instanceof ClientSessionChannel.MessageListener) {
                    notifyOnMessage((MessageListener)listener, message);
                }
            }
            for (ClientSessionChannelListener listener : _subscriptions) {
                if (listener instanceof ClientSessionChannel.MessageListener) {
                    if (!message.isPublishReply()) {
                        notifyOnMessage((MessageListener)listener, message);
                    }
                }
            }
        }

        protected void notifyOnMessage(MessageListener listener, Message message) {
            throwIfReleased();
            try {
                listener.onMessage(this, message);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }

        @Override
        public void setAttribute(String name, Object value) {
            throwIfReleased();
            _attributes.setAttribute(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            throwIfReleased();
            return _attributes.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNames() {
            throwIfReleased();
            return _attributes.getAttributeNameSet();
        }

        @Override
        public Object removeAttribute(String name) {
            throwIfReleased();
            Object old = getAttribute(name);
            _attributes.removeAttribute(name);
            return old;
        }

        protected void throwIfReleased() {
            if (isReleased()) {
                throw new IllegalStateException("Channel " + this + " has been released");
            }
        }

        @Override
        public String dump() {
            return ContainerLifeCycle.dump(this);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException {
            ContainerLifeCycle.dumpObject(out, this);

            List<Dumpable> children = new ArrayList<>();

            children.add(new Dumpable() {
                @Override
                public String dump() {
                    return null;
                }

                @Override
                public void dump(Appendable out, String indent) throws IOException {
                    List<ClientSessionChannelListener> listeners = getListeners();
                    ContainerLifeCycle.dumpObject(out, "listeners: " + listeners.size());
                    ContainerLifeCycle.dump(out, indent, listeners);
                }
            });

            children.add(new Dumpable() {
                @Override
                public String dump() {
                    return null;
                }

                @Override
                public void dump(Appendable out, String indent) throws IOException {
                    List<MessageListener> subscribers = getSubscribers();
                    ContainerLifeCycle.dumpObject(out, "subscribers: " + subscribers.size());
                    ContainerLifeCycle.dump(out, indent, subscribers);
                }
            });

            ContainerLifeCycle.dump(out, indent, children);
        }

        @Override
        public String toString() {
            return String.format("%s@%x[%s]", _id, hashCode(), AbstractClientSession.this);
        }
    }
}
