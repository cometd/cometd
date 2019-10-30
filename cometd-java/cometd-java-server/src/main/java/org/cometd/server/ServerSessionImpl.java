/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.server;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.server.AbstractServerTransport.Scheduler;
import org.cometd.server.transport.AbstractHttpTransport;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerSessionImpl implements ServerSession, Dumpable {
    private static final AtomicLong _idCount = new AtomicLong();

    private static final Logger _logger = LoggerFactory.getLogger(ServerSession.class);
    private final BayeuxServerImpl _bayeux;
    private final String _id;
    private final List<ServerSessionListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final Queue<ServerMessage> _queue = new ArrayDeque<>();
    private final LocalSessionImpl _localSession;
    private final AttributesMap _attributes = new AttributesMap();
    private final AtomicBoolean _connected = new AtomicBoolean();
    private final AtomicBoolean _disconnected = new AtomicBoolean();
    private final AtomicBoolean _handshook = new AtomicBoolean();
    private final Map<ServerChannelImpl, Boolean> _subscribedTo = new ConcurrentHashMap<>();
    private final LazyTask _lazyTask = new LazyTask();
    private AbstractServerTransport.Scheduler _scheduler;
    private ServerTransport _advisedTransport;
    private int _maxQueue = -1;
    private long _transientTimeout = -1;
    private long _transientInterval = -1;
    private long _timeout = -1;
    private long _interval = -1;
    private long _maxInterval = -1;
    private long _maxProcessing = -1;
    private long _maxLazy = -1;
    private boolean _metaConnectDelivery;
    private int _batch;
    private String _userAgent;
    private long _messageTime;
    private long _scheduleTime;
    private long _expireTime;
    private boolean _nonLazyMessages;
    private boolean _broadcastToPublisher;
    private boolean _allowMessageDeliveryDuringHandshake;
    private String _browserId;

    public ServerSessionImpl(BayeuxServerImpl bayeux) {
        this(bayeux, null, null);
    }

    public ServerSessionImpl(BayeuxServerImpl bayeux, LocalSessionImpl localSession, String idHint) {
        _bayeux = bayeux;
        _localSession = localSession;

        StringBuilder id = new StringBuilder(30);
        int len = 20;
        if (idHint != null) {
            len += idHint.length() + 1;
            id.append(idHint);
            id.append('_');
        }
        int index = id.length();

        while (id.length() < len) {
            id.append(Long.toString(_bayeux.randomLong(), 36));
        }

        id.insert(index, Long.toString(_idCount.incrementAndGet(), 36));

        _id = id.toString();

        ServerTransport transport = _bayeux.getCurrentTransport();
        if (transport != null) {
            _maxInterval = transport.getMaxInterval();
        }

        _broadcastToPublisher = _bayeux.isBroadcastToPublisher();
    }

    public BayeuxServerImpl getBayeuxServer() {
        return _bayeux;
    }

    /**
     * @return the remote user agent
     */
    @Override
    public String getUserAgent() {
        return _userAgent;
    }

    /**
     * @param userAgent the remote user agent
     */
    public void setUserAgent(String userAgent) {
        _userAgent = userAgent;
    }

    /**
     * @return the remote client identifier
     */
    public String getBrowserId() {
        return _browserId;
    }

    /**
     * <p>Sets a remote client identifier, typically a browser.</p>
     *
     * @param browserId the remote client identifier
     */
    public void setBrowserId(String browserId) {
        _browserId = browserId;
    }

    protected void sweep(long now) {
        if (isLocalSession()) {
            return;
        }

        boolean remove = false;
        Scheduler scheduler = null;
        synchronized (getLock()) {
            if (_expireTime == 0) {
                if (_maxProcessing > 0 && now > _messageTime + _maxProcessing) {
                    _logger.info("Sweeping session during processing {}", this);
                    remove = true;
                }
            } else {
                if (now > _expireTime) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Sweeping session {}", this);
                    }
                    remove = true;
                }
            }
            if (remove) {
                scheduler = _scheduler;
            }
        }
        if (remove) {
            if (scheduler != null) {
                scheduler.cancel();
            }
            _bayeux.removeServerSession(this, true);
        }
    }

    @Override
    public Set<ServerChannel> getSubscriptions() {
        return Collections.<ServerChannel>unmodifiableSet(_subscribedTo.keySet());
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

    @Override
    public void batch(Runnable batch) {
        startBatch();
        try {
            batch.run();
        } finally {
            endBatch();
        }
    }

    @Override
    public void deliver(Session sender, ServerMessage.Mutable message) {
        ServerSession session = null;
        if (sender instanceof ServerSession) {
            session = (ServerSession)sender;
        } else if (sender instanceof LocalSession) {
            session = ((LocalSession)sender).getServerSession();
        }

        if (_bayeux.extendSend(session, this, message)) {
            doDeliver(session, message);
        }
    }

    @Override
    public void deliver(Session sender, String channelId, Object data) {
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setChannel(channelId);
        message.setData(data);
        deliver(sender, message);
    }

    protected void doDeliver(ServerSession sender, ServerMessage.Mutable mutable) {
        if (sender == this && !isBroadcastToPublisher() && ChannelId.isBroadcast(mutable.getChannel())) {
            return;
        }

        ServerMessage.Mutable message = extendSend(mutable);
        if (message == null) {
            return;
        }

        _bayeux.freeze(message);

        for (ServerSessionListener listener : _listeners) {
            if (listener instanceof MessageListener) {
                if (!notifyOnMessage((MessageListener)listener, sender, message)) {
                    return;
                }
            }
        }

        Boolean wakeup = enqueueMessage(sender, message);
        if (wakeup == null) {
            return;
        }

        if (wakeup) {
            if (message.isLazy()) {
                flushLazy(message);
            } else {
                flush();
            }
        }
    }

    private Boolean enqueueMessage(ServerSession sender, ServerMessage.Mutable message) {
        synchronized (getLock()) {
            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof MaxQueueListener) {
                    final int maxQueueSize = _maxQueue;
                    if (maxQueueSize > 0 && _queue.size() >= maxQueueSize) {
                        if (!notifyQueueMaxed((MaxQueueListener)listener, this, _queue, sender, message)) {
                            return null;
                        }
                    }
                }
            }
            addMessage(message);
            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof QueueListener) {
                    notifyQueued((QueueListener)listener, sender, message);
                }
            }
            return _batch == 0;
        }
    }

    protected ServerMessage.Mutable extendSend(ServerMessage.Mutable mutable) {
        ListIterator<Extension> i = _extensions.listIterator();
        while (i.hasNext()) {
            i.next();
        }
        while (i.hasPrevious()) {
            Extension extension = i.previous();
            if (mutable.isMeta()) {
                if (!notifySendMeta(extension, mutable)) {
                    return null;
                }
            } else {
                mutable = notifySend(extension, mutable);
                if (mutable == null) {
                    return null;
                }
            }
        }
        return mutable;
    }

    private boolean notifyQueueMaxed(MaxQueueListener listener, ServerSession session, Queue<ServerMessage> queue, ServerSession sender, ServerMessage message) {
        try {
            return listener.queueMaxed(session, queue, sender, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    private boolean notifyOnMessage(MessageListener listener, ServerSession from, ServerMessage message) {
        try {
            return listener.onMessage(this, from, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    private void notifyQueued(QueueListener listener, ServerSession session, ServerMessage message) {
        try {
            listener.queued(session, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected void handshake() {
        _handshook.set(true);

        AbstractServerTransport transport = (AbstractServerTransport)_bayeux.getCurrentTransport();
        if (transport != null) {
            _maxQueue = transport.getOption(AbstractServerTransport.MAX_QUEUE_OPTION, -1);
            _maxInterval = transport.getMaxInterval();
            _maxProcessing = transport.getOption(AbstractServerTransport.MAX_PROCESSING_OPTION, -1);
            _maxLazy = transport.getMaxLazyTimeout();
        }
    }

    protected void connected() {
        _connected.set(true);
    }

    @Override
    public void disconnect() {
        boolean connected = _bayeux.removeServerSession(this, false);
        if (connected) {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setSuccessful(true);
            message.setChannel(Channel.META_DISCONNECT);
            deliver(this, message);
            flush();
        }
    }

    @Override
    public void startBatch() {
        synchronized (getLock()) {
            ++_batch;
        }
    }

    @Override
    public boolean endBatch() {
        boolean result = false;
        synchronized (getLock()) {
            if (--_batch == 0 && _nonLazyMessages) {
                result = true;
            }
        }
        if (result) {
            flush();
        }
        return result;
    }

    @Override
    public LocalSession getLocalSession() {
        return _localSession;
    }

    @Override
    public boolean isLocalSession() {
        return _localSession != null;
    }

    @Override
    public void addListener(ServerSessionListener listener) {
        _listeners.add(listener);
    }

    @Override
    public String getId() {
        return _id;
    }

    public Object getLock() {
        return this;
    }

    public Queue<ServerMessage> getQueue() {
        return _queue;
    }

    public boolean hasNonLazyMessages() {
        synchronized (getLock()) {
            return _nonLazyMessages;
        }
    }

    protected void addMessage(ServerMessage message) {
        synchronized (getLock()) {
            _queue.add(message);
            _nonLazyMessages |= !message.isLazy();
        }
    }

    public List<ServerMessage> takeQueue() {
        List<ServerMessage> copy = Collections.emptyList();
        synchronized (getLock()) {
            // Always call listeners, even if the queue is
            // empty since they may add messages to the queue.
            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof DeQueueListener) {
                    notifyDeQueue((DeQueueListener)listener, this, _queue);
                }
            }

            int size = _queue.size();
            if (size > 0) {
                copy = new ArrayList<>(size);
                copy.addAll(_queue);
                _queue.clear();
            }

            _nonLazyMessages = false;
        }
        return copy;
    }

    private void notifyDeQueue(DeQueueListener listener, ServerSession serverSession, Queue<ServerMessage> queue) {
        try {
            listener.deQueue(serverSession, queue);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    @Override
    public void removeListener(ServerSessionListener listener) {
        _listeners.remove(listener);
    }

    public List<ServerSessionListener> getListeners() {
        return Collections.unmodifiableList(_listeners);
    }

    public void setScheduler(AbstractServerTransport.Scheduler newScheduler) {
        if (newScheduler == null) {
            Scheduler oldScheduler;
            synchronized (getLock()) {
                oldScheduler = _scheduler;
                if (oldScheduler != null) {
                    _scheduler = null;
                }
            }
            if (oldScheduler != null) {
                oldScheduler.cancel();
            }
        } else {
            Scheduler oldScheduler;
            boolean schedule = false;
            synchronized (getLock()) {
                oldScheduler = _scheduler;
                _scheduler = newScheduler;
                if (shouldSchedule()) {
                    schedule = true;
                    if (newScheduler instanceof AbstractHttpTransport.HttpScheduler) {
                        _scheduler = null;
                    }
                }
            }
            if (oldScheduler != null && oldScheduler != newScheduler) {
                oldScheduler.cancel();
            }
            if (schedule) {
                newScheduler.schedule();
            }
        }
    }

    public boolean shouldSchedule() {
        synchronized (getLock()) {
            return hasNonLazyMessages() && _batch == 0;
        }
    }

    public void flush() {
        Scheduler scheduler;
        synchronized (getLock()) {
            _lazyTask.cancel();

            scheduler = _scheduler;

            if (scheduler != null) {
                if (scheduler instanceof AbstractHttpTransport.HttpScheduler) {
                    _scheduler = null;
                }
            }
        }
        if (scheduler != null) {
            scheduler.schedule();
            // If there is a scheduler, then it's a remote session
            // and we should not perform local delivery, so we return
            return;
        }

        // do local delivery
        if (_localSession != null && hasNonLazyMessages()) {
            for (ServerMessage msg : takeQueue()) {
                _localSession.receive(new HashMapMessage(msg));
            }
        }
    }

    private void flushLazy(ServerMessage message) {
        synchronized (getLock()) {
            ServerChannel channel = _bayeux.getChannel(message.getChannel());
            long lazyTimeout = -1;
            if (channel != null) {
                lazyTimeout = channel.getLazyTimeout();
            }
            if (lazyTimeout <= 0) {
                lazyTimeout = _maxLazy;
            }

            if (lazyTimeout <= 0) {
                flush();
            } else {
                _lazyTask.schedule(lazyTimeout);
            }
        }
    }

    public void cancelSchedule() {
        Scheduler scheduler;
        synchronized (getLock()) {
            scheduler = _scheduler;
            if (scheduler != null) {
                _scheduler = null;
            }
        }
        if (scheduler != null) {
            scheduler.cancel();
        }
    }

    public void cancelExpiration(boolean metaConnect) {
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            _messageTime = now;
            if (metaConnect) {
                _expireTime = 0;
            } else if (_expireTime != 0) {
                _expireTime += now - _scheduleTime;
            }
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("{} expiration for {}", metaConnect ? "Cancelling" : "Delaying", this);
        }
    }

    public void scheduleExpiration(long defaultInterval) {
        long interval = calculateInterval(defaultInterval);
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            _scheduleTime = now;
            _expireTime = now + interval + _maxInterval;
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("Scheduled expiration for {}", this);
        }
    }

    protected long getMaxInterval() {
        return _maxInterval;
    }

    long getIntervalTimestamp() {
        return _expireTime;
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
        Object old = getAttribute(name);
        _attributes.removeAttribute(name);
        return old;
    }

    @Override
    public void setAttribute(String name, Object value) {
        _attributes.setAttribute(name, value);
    }

    @Override
    public boolean isHandshook() {
        return _handshook.get();
    }

    @Override
    public boolean isConnected() {
        return _connected.get();
    }

    public boolean isDisconnected() {
        return _disconnected.get();
    }

    protected boolean extendRecv(ServerMessage.Mutable message) {
        for (Extension extension : _extensions) {
            boolean proceed = message.isMeta() ?
                    notifyRcvMeta(extension, message) :
                    notifyRcv(extension, message);
            if (!proceed) {
                return false;
            }
        }
        return true;
    }

    private boolean notifyRcvMeta(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.rcvMeta(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifyRcv(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.rcv(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifySendMeta(Extension extension, ServerMessage.Mutable message) {
        try {
            return extension.sendMeta(this, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private ServerMessage.Mutable notifySend(Extension extension, ServerMessage.Mutable message) {
        try {
            ServerMessage result = extension.send(this, message);
            if (result instanceof ServerMessage.Mutable) {
                return (ServerMessage.Mutable)result;
            } else {
                return result == null ? null : _bayeux.newMessage(result);
            }
        } catch (Throwable x) {
            _logger.info("Exception while invoking extension " + extension, x);
            return message;
        }
    }

    public void reAdvise() {
        _advisedTransport = null;
    }

    public Map<String, Object> takeAdvice(ServerTransport transport) {
        if (transport != null && transport != _advisedTransport) {
            _advisedTransport = transport;

            // The timeout is calculated based on the values of the session/transport
            // because we want to send to the client the *next* timeout
            long timeout = getTimeout() < 0 ? transport.getTimeout() : getTimeout();

            // The interval is calculated using also the transient value
            // because we want to send to the client the *current* interval
            long interval = calculateInterval(transport.getInterval());

            Map<String, Object> advice = new HashMap<>(3);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
            advice.put(Message.INTERVAL_FIELD, interval);
            advice.put(Message.TIMEOUT_FIELD, timeout);
            if (transport instanceof AbstractServerTransport) {
                if (((AbstractServerTransport)transport).isHandshakeReconnect()) {
                    advice.put(Message.MAX_INTERVAL_FIELD, getMaxInterval());
                }
            }
            return advice;
        }

        // advice has not changed, so return null.
        return null;
    }

    @Override
    public long getTimeout() {
        return _timeout;
    }

    @Override
    public long getInterval() {
        return _interval;
    }

    @Override
    public void setTimeout(long timeoutMS) {
        _timeout = timeoutMS;
        _advisedTransport = null;
    }

    @Override
    public void setInterval(long intervalMS) {
        _interval = intervalMS;
        _advisedTransport = null;
    }

    public boolean isBroadcastToPublisher() {
        return _broadcastToPublisher;
    }

    public void setBroadcastToPublisher(boolean value) {
        _broadcastToPublisher = value;
    }

    /**
     * @param timedOut whether the session has been timed out
     * @return True if the session was connected.
     */
    protected boolean removed(boolean timedOut) {
        if (!timedOut) {
            _disconnected.set(true);
        }
        boolean connected = _connected.getAndSet(false);
        boolean handshook = _handshook.getAndSet(false);
        if (connected || handshook) {
            for (ServerChannelImpl channel : _subscribedTo.keySet()) {
                channel.unsubscribe(this);
            }

            for (ServerSessionListener listener : _listeners) {
                if (listener instanceof ServerSession.RemoveListener) {
                    notifyRemoved((RemoveListener)listener, this, timedOut);
                }
            }
        }
        return connected;
    }

    private void notifyRemoved(RemoveListener listener, ServerSession serverSession, boolean timedout) {
        try {
            listener.removed(serverSession, timedout);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    public void setMetaConnectDeliveryOnly(boolean meta) {
        _metaConnectDelivery = meta;
    }

    public boolean isMetaConnectDeliveryOnly() {
        return _metaConnectDelivery;
    }

    public boolean isAllowMessageDeliveryDuringHandshake() {
        return _allowMessageDeliveryDuringHandshake;
    }

    public void setAllowMessageDeliveryDuringHandshake(boolean allow) {
        _allowMessageDeliveryDuringHandshake = allow;
    }

    protected void subscribedTo(ServerChannelImpl channel) {
        _subscribedTo.put(channel, Boolean.TRUE);
    }

    protected void unsubscribedFrom(ServerChannelImpl channel) {
        _subscribedTo.remove(channel);
    }

    public long calculateTimeout(long defaultTimeout) {
        if (_transientTimeout >= 0) {
            return _transientTimeout;
        }

        if (_timeout >= 0) {
            return _timeout;
        }

        return defaultTimeout;
    }

    public long calculateInterval(long defaultInterval) {
        if (_transientInterval >= 0) {
            return _transientInterval;
        }

        if (_interval >= 0) {
            return _interval;
        }

        return defaultInterval;
    }

    /**
     * Updates the transient timeout with the given value.
     * The transient timeout is the one sent by the client, that should
     * temporarily override the session/transport timeout, for example
     * when the client sends {timeout:0}
     *
     * @param timeout the value to update the timeout to
     * @see #updateTransientInterval(long)
     */
    public void updateTransientTimeout(long timeout) {
        _transientTimeout = timeout;
    }

    /**
     * Updates the transient interval with the given value.
     * The transient interval is the one sent by the client, that should
     * temporarily override the session/transport interval, for example
     * when the client sends {timeout:0,interval:60000}
     *
     * @param interval the value to update the interval to
     * @see #updateTransientTimeout(long)
     */
    public void updateTransientInterval(long interval) {
        _transientInterval = interval;
    }

    @Override
    public String dump() {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        ContainerLifeCycle.dumpObject(out, this);

        List<Object> children = new ArrayList<>();

        children.add(new Dumpable() {
            @Override
            public String dump() {
                return null;
            }

            @Override
            public void dump(Appendable out, String indent) throws IOException {
                List<ServerSessionListener> listeners = getListeners();
                ContainerLifeCycle.dumpObject(out, "listeners: " + listeners.size());
                if (_bayeux.isDetailedDump()) {
                    ContainerLifeCycle.dump(out, indent, listeners);
                }
            }
        });

        ContainerLifeCycle.dump(out, indent, children);
    }

    @Override
    public String toString() {
        long last;
        long expire;
        long now = System.currentTimeMillis();
        synchronized (getLock()) {
            last = now - _messageTime;
            expire = _expireTime == 0 ? 0 : _expireTime - now;
        }
        return String.format("%s,last=%d,expire=%d", _id, last, expire);
    }

    private class LazyTask implements Runnable {
        private long _execution;
        private volatile org.eclipse.jetty.util.thread.Scheduler.Task _task;

        @Override
        public void run() {
            flush();
            _execution = 0;
            _task = null;
        }

        public boolean cancel() {
            org.eclipse.jetty.util.thread.Scheduler.Task task = _task;
            return task != null && task.cancel();
        }

        public boolean schedule(long lazyTimeout) {
            long execution = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lazyTimeout);
            if (_task == null || execution < _execution) {
                cancel();
                _execution = execution;
                _task = _bayeux.schedule(this, lazyTimeout);
                return true;
            }
            return false;
        }
    }
}
