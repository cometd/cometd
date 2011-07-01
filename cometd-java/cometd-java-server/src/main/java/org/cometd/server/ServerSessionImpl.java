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

package org.cometd.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.server.AbstractServerTransport.OneTimeScheduler;
import org.cometd.server.AbstractServerTransport.Scheduler;
import org.cometd.server.transport.HttpTransport;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.ajax.JSON;
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
    private final LocalSessionImpl _localSession;
    private final AttributesMap _attributes = new AttributesMap();
    private final AtomicBoolean _connected = new AtomicBoolean();
    private final AtomicBoolean _handshook = new AtomicBoolean();
    private final Map<ServerChannelImpl, Boolean> _subscribedTo = new ConcurrentHashMap<ServerChannelImpl, Boolean>();

    private AbstractServerTransport.Scheduler _scheduler;
    private ServerTransport _advisedTransport;

    private int _maxQueue=-1;
    private long _transientTimeout=-1;
    private long _transientInterval=-1;
    private long _timeout=-1;
    private long _interval=-1;
    private long _maxInterval=-1;
    private long _maxLazy=-1;
    private long _maxConnectDelay=-1;
    private boolean _metaConnectDelivery;
    private int _batch;
    private String _userAgent;
    private long _connectTimestamp=-1;
    private long _intervalTimestamp;
    private long _lastInterval;
    private long _lastConnect;
    private volatile boolean _lazyDispatch;

    private Task _lazyTask;

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux)
    {
        this(bayeux,null,null);
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl(BayeuxServerImpl bayeux, LocalSessionImpl localSession, String idHint)
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
    /** Get the userAgent.
     * @return the userAgent
     */
    public String getUserAgent()
    {
        return _userAgent;
    }

    /* ------------------------------------------------------------ */
    /** Set the userAgent.
     * @param userAgent the userAgent to set
     */
    public void setUserAgent(String userAgent)
    {
        _userAgent = userAgent;
    }

    /* ------------------------------------------------------------ */
    protected void sweep(long now)
    {
        boolean remove = false;
        synchronized (_queue)
        {
            if (_intervalTimestamp == 0)
            {
                if (now > _connectTimestamp + _maxConnectDelay)
                {
                    _logger.info("Emergency sweeping session {}", this);
                    remove = true;
                }
            }
            else
            {
                if (now > _intervalTimestamp)
                {
                    _logger.debug("Sweeping session {}", this);
                    remove = true;
                }
            }
            if (remove && _scheduler != null)
                _scheduler.cancel();
        }
        if (remove)
            _bayeux.removeServerSession(this, true);
    }

    /* ------------------------------------------------------------ */
    protected List<Extension> getExtensions()
    {
        return Collections.unmodifiableList(_extensions);
    }

    public Set<ServerChannel> getSubscriptions()
    {
        return Collections.<ServerChannel>unmodifiableSet(_subscribedTo.keySet());
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
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
    public void deliver(Session from, Mutable message)
    {
        ServerSession session;
        if (from instanceof ServerSession)
            session = (ServerSession)from;
        else
            session = ((LocalSession)from).getServerSession();

        if (!_bayeux.extendSend(session, this, message))
            return;

        doDeliver(session, message);
    }

    /* ------------------------------------------------------------ */
    public void deliver(Session from, String channelId, Object data, String id)
    {
        ServerMessage.Mutable message = _bayeux.newMessage();
        message.setChannel(channelId);
        message.setData(data);
        message.setId(id);
        deliver(from, message);
    }

    /* ------------------------------------------------------------ */
    protected void doDeliver(ServerSession from, ServerMessage.Mutable mutable)
    {
        ServerMessage message = null;
        if (mutable.isMeta())
        {
            if (extendSendMeta(mutable))
                message = mutable;
        }
        else
        {
            message = extendSendMessage(mutable);
        }

        if (message == null)
            return;

        for (ServerSessionListener listener : _listeners)
        {
            if (listener instanceof MaxQueueListener && _maxQueue >=0 && _queue.size() >= _maxQueue)
            {
                if (!notifyQueueMaxed((MaxQueueListener)listener, from, message))
                    return;
            }
            if (listener instanceof MessageListener)
            {
                if (!notifyOnMessage((MessageListener)listener, from, message))
                    return;
            }
        }

        boolean wakeup;
        synchronized (_queue)
        {
            _queue.add(message);
            wakeup = _batch==0;
        }

        if (wakeup)
        {
            if (message.isLazy())
                flushLazy();
            else
                flush();
        }
    }

    private boolean notifyQueueMaxed(MaxQueueListener listener, ServerSession from, ServerMessage message)
    {
        try
        {
            return listener.queueMaxed(this, from, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    private boolean notifyOnMessage(MessageListener listener, ServerSession from, ServerMessage message)
    {
        try
        {
            return listener.onMessage(this, from, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    protected void handshake()
    {
        _handshook.set(true);
    }

    /* ------------------------------------------------------------ */
    protected void connect()
    {
        _connected.set(true);

        synchronized (_queue)
        {
            if (_connectTimestamp == -1)
            {
                HttpTransport transport = (HttpTransport)_bayeux.getCurrentTransport();
                if (transport != null)
                {
                    _maxQueue = transport.getOption("maxQueue", -1);
                    _maxInterval = _interval >= 0 ? (_interval + transport.getMaxInterval() - transport.getInterval()) : transport.getMaxInterval();
                    _maxLazy = transport.getMaxLazyTimeout();
                    _maxConnectDelay = transport.getOption("maxConnectDelay", 16 * 60 * 1000L);

                    if (_maxLazy > 0)
                    {
                        _lazyTask = new Timeout.Task()
                        {
                            @Override
                            public void expired()
                            {
                                _lazyDispatch = false;
                                flush();
                            }

                            @Override
                            public String toString()
                            {
                                return "LazyTask@" + getId();
                            }
                        };
                    }
                }
            }
            cancelIntervalTimeout();
        }
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        boolean connected = _bayeux.removeServerSession(this, false);
        if (connected)
        {
            ServerMessage.Mutable message = _bayeux.newMessage();
            message.setChannel(Channel.META_DISCONNECT);
            message.setSuccessful(true);
            deliver(this, message);
            if (_queue.size() > 0)
                flush();
        }
    }

    /* ------------------------------------------------------------ */
    public boolean endBatch()
    {
        synchronized (_queue)
        {
            if (--_batch == 0 && _queue.size() > 0)
            {
                flush();
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public LocalSession getLocalSession()
    {
        return _localSession;
    }

    /* ------------------------------------------------------------ */
    public boolean isLocalSession()
    {
        return _localSession != null;
    }

    /* ------------------------------------------------------------ */
    public void startBatch()
    {
        synchronized (_queue)
        {
            ++_batch;
        }
    }

    /* ------------------------------------------------------------ */
    public void addListener(ServerSessionListener listener)
    {
        _listeners.add(listener);
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return _id;
    }

    /* ------------------------------------------------------------ */
    public Object getLock()
    {
        return _queue;
    }

    /* ------------------------------------------------------------ */
    public Queue<ServerMessage> getQueue()
    {
        return _queue;
    }

    /* ------------------------------------------------------------ */
    public boolean isQueueEmpty()
    {
        synchronized (_queue)
        {
            return _queue.size() == 0;
        }
    }

    /* ------------------------------------------------------------ */
    public void addQueue(ServerMessage message)
    {
        synchronized (_queue)
        {
            _queue.add(message);
        }
    }

    /* ------------------------------------------------------------ */
    public void replaceQueue(List<ServerMessage> queue)
    {
        synchronized (_queue)
        {
            _queue.clear();
            _queue.addAll(queue);
        }
    }

    /* ------------------------------------------------------------ */
    public List<ServerMessage> takeQueue()
    {
        List<ServerMessage> copy = new ArrayList<ServerMessage>();
        synchronized (_queue)
        {
            if (!_queue.isEmpty())
            {
                for (ServerSessionListener listener : _listeners)
                {
                    if (listener instanceof DeQueueListener)
                        notifyDeQueue((DeQueueListener)listener, this, _queue);
                }
                copy.addAll(_queue);
                _queue.clear();
            }
        }
        return copy;
    }

    private void notifyDeQueue(DeQueueListener listener, ServerSession serverSession, Queue<ServerMessage> queue)
    {
        try
        {
            listener.deQueue(serverSession, queue);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    /* ------------------------------------------------------------ */
    public void removeListener(ServerSessionListener listener)
    {
        _listeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    public void setScheduler(AbstractServerTransport.Scheduler scheduler)
    {
        synchronized(_queue)
        {
            if (scheduler == null)
            {
                if (_scheduler != null)
                {
                    _scheduler.cancel();
                    _scheduler = null;
                }
            }
            else
            {
                if (_scheduler != null && _scheduler != scheduler)
                {
                    _scheduler.cancel();
                }

                _scheduler = scheduler;

                if (_queue.size() > 0 && _batch == 0)
                {
                    _scheduler.schedule();
                    if (_scheduler instanceof OneTimeScheduler)
                        _scheduler = null;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void flush()
    {
        synchronized (_queue)
        {
            if (_lazyDispatch && _lazyTask != null)
                _bayeux.cancelTimeout(_lazyTask);

            Scheduler scheduler = _scheduler;
            if (scheduler != null)
            {
                if (_scheduler instanceof OneTimeScheduler)
                    _scheduler = null;
                scheduler.schedule();
                return;
            }
        }

        // do local delivery
        if (_localSession != null && _queue.size() > 0)
        {
            for (ServerMessage msg : takeQueue())
            {
                if (msg instanceof Message.Mutable)
                    _localSession.receive((Message.Mutable)msg);
                else
                    _localSession.receive(new HashMapMessage(msg));
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void flushLazy()
    {
        synchronized (_queue)
        {
            if (_maxLazy == 0)
                flush();
            else if (_maxLazy > 0 && !_lazyDispatch)
            {
                _lazyDispatch = true;
                _bayeux.startTimeout(_lazyTask, _connectTimestamp % _maxLazy);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void cancelSchedule()
    {
        synchronized (_queue)
        {
            Scheduler scheduler = _scheduler;
            if (scheduler != null)
            {
                _scheduler = null;
                scheduler.cancel();
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void cancelIntervalTimeout()
    {
        synchronized (_queue)
        {
            long now = System.currentTimeMillis();
            if (_intervalTimestamp > 0)
                _lastInterval = now - (_intervalTimestamp - _maxInterval);
            _connectTimestamp = now;
            _intervalTimestamp = 0;
        }
    }

    /* ------------------------------------------------------------ */
    public void startIntervalTimeout()
    {
        synchronized (_queue)
        {
            long now = System.currentTimeMillis();
            _lastConnect = now - _connectTimestamp;
            _intervalTimestamp = now + _maxInterval;
        }
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
            for (Extension extension : _extensions)
                if (!notifyRcvMeta(extension, message))
                    return false;
        }
        else
        {
            for (Extension extension : _extensions)
                if (!notifyRcv(extension, message))
                    return false;
        }
        return true;
    }

    private boolean notifyRcvMeta(Extension extension, Mutable message)
    {
        try
        {
            return extension.rcvMeta(this, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifyRcv(Extension extension, Mutable message)
    {
        try
        {
            return extension.rcv(this, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSendMeta(ServerMessage.Mutable message)
    {
        if (!message.isMeta())
            throw new IllegalStateException();

        for (Extension extension : _extensions)
            if (!notifySendMeta(extension, message))
                return false;

        return true;
    }

    private boolean notifySendMeta(Extension extension, Mutable message)
    {
        try
        {
            return extension.sendMeta(this, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    protected ServerMessage extendSendMessage(ServerMessage message)
    {
        if (message.isMeta())
            throw new IllegalStateException();

        for (Extension extension : _extensions)
        {
            message = notifySend(extension, message);
            if (message == null)
                return null;
        }

        return message;
    }

    private ServerMessage notifySend(Extension extension, ServerMessage message)
    {
        try
        {
            return extension.send(this, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return message;
        }
    }

    /* ------------------------------------------------------------ */
    public Object getAdvice()
    {
        final ServerTransport transport = _bayeux.getCurrentTransport();
        if (transport == null)
            return null;

        long timeout = getTimeout() < 0 ? transport.getTimeout() : getTimeout();
        long interval = getInterval() < 0 ? transport.getInterval() : getInterval();

        return new JSON.Literal("{\"reconnect\":\"retry\"," +
                "\"interval\":" + interval + "," +
                "\"timeout\":" + timeout + "}");
    }

    /* ------------------------------------------------------------ */
    public void reAdvise()
    {
        _advisedTransport = null;
    }

    /* ------------------------------------------------------------ */
    public Object takeAdvice()
    {
        final ServerTransport transport = _bayeux.getCurrentTransport();

        if (transport != null && transport != _advisedTransport)
        {
            _advisedTransport = transport;
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
        _timeout = timeoutMS;
        _advisedTransport = null;
    }

    /* ------------------------------------------------------------ */
    public void setInterval(long intervalMS)
    {
        _interval = intervalMS;
        _advisedTransport = null;
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
            for (ServerChannelImpl channel : _subscribedTo.keySet())
                channel.unsubscribe(this);

            for (ServerSessionListener listener : _listeners)
            {
                if (listener instanceof ServerSession.RemoveListener)
                    notifyRemoved((RemoveListener)listener, this, timedout);
            }
        }
        return connected;
    }

    private void notifyRemoved(RemoveListener listener, ServerSession serverSession, boolean timedout)
    {
        try
        {
            listener.removed(serverSession, timedout);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDeliveryOnly(boolean meta)
    {
        _metaConnectDelivery = meta;
    }

    /* ------------------------------------------------------------ */
    public boolean isMetaConnectDeliveryOnly()
    {
        return _metaConnectDelivery;
    }

    /* ------------------------------------------------------------ */
    protected void subscribedTo(ServerChannelImpl channel)
    {
        _subscribedTo.put(channel, Boolean.TRUE);
    }

    /* ------------------------------------------------------------ */
    protected void unsubscribedFrom(ServerChannelImpl channel)
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
            _localSession.dump(b, indent + "   ");
        }
    }

    /* ------------------------------------------------------------ */
    public String toDetailString()
    {
        return _id + ",lc=" + _lastConnect + ",li=" + _lastInterval;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _id;
    }

    public long calculateTimeout(long defaultTimeout)
    {
        if (_transientTimeout >= 0)
            return _transientTimeout;

        if (_timeout >= 0)
            return _timeout;

        return defaultTimeout;
    }

    public long calculateInterval(long defaultInterval)
    {
        if (_transientInterval >= 0)
            return _transientInterval;

        if (_interval >= 0)
            return _interval;

        return defaultInterval;
    }

    /**
     * Updates the transient timeout with the given value.
     * @param timeout the value to update the timeout to
     * @see #updateTransientInterval(long)
     */
    public void updateTransientTimeout(long timeout)
    {
        _transientTimeout = timeout;
    }

    /**
     * Updates the transient timeout with the given value.
     * @param interval the value to update the interval to
     * @see #updateTransientTimeout(long)
     */
    public void updateTransientInterval(long interval)
    {
        _transientInterval = interval;
    }
}
