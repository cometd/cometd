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
package org.cometd.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerChannelImpl implements ServerChannel, Dumpable {
    private static final Logger _logger = LoggerFactory.getLogger(ServerChannel.class);
    private final BayeuxServerImpl _bayeux;
    private final ChannelId _id;
    private final AttributesMap _attributes = new AttributesMap();
    private final Set<ServerSession> _subscribers = new CopyOnWriteArraySet<>();
    private final List<ServerChannelListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Authorizer> _authorizers = new CopyOnWriteArrayList<>();
    private final CountDownLatch _initialized = new CountDownLatch(1);
    private final AtomicInteger _sweeperPasses = new AtomicInteger();
    private boolean _lazy;
    private long _lazyTimeout = -1;
    private boolean _persistent;
    private boolean _broadcastToPublisher = true;

    protected ServerChannelImpl(BayeuxServerImpl bayeux, ChannelId id) {
        _bayeux = bayeux;
        _id = id;
        setPersistent(!isBroadcast());
    }

    /**
     * Waits for the channel to be {@link #initialized() initialized}, to avoid
     * that channels are returned to applications in a half-initialized state,
     * in particular before {@link Initializer}s have run.
     *
     * @see BayeuxServerImpl#createChannelIfAbsent(String, Initializer...)
     */
    void waitForInitialized() {
        try {
            if (!_initialized.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Not Initialized: " + this);
            }
        } catch (InterruptedException x) {
            throw new IllegalStateException("Initialization interrupted: " + this, x);
        }
    }

    /**
     * Marks this channel as initialized, notifying other threads that may
     * {@link #waitForInitialized() wait for initialization}.
     */
    void initialized() {
        resetSweeperPasses();
        _initialized.countDown();
    }

    void resetSweeperPasses() {
        _sweeperPasses.set(0);
    }

    @Override
    public boolean subscribe(ServerSession session) {
        return subscribe((ServerSessionImpl)session, null);
    }

    protected boolean subscribe(ServerSessionImpl session, ServerMessage message) {
        if (isService()) {
            // Subscription to service channels is a no operation.
            return true;
        }

        if (isMeta()) {
            return false;
        }

        resetSweeperPasses();

        if (session.subscribe(this)) {
            if (_subscribers.add(session)) {
                for (ServerChannelListener listener : _listeners) {
                    if (listener instanceof SubscriptionListener) {
                        notifySubscribed((SubscriptionListener)listener, session, this, message);
                    }
                }
                for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners()) {
                    if (listener instanceof BayeuxServer.SubscriptionListener) {
                        notifySubscribed((BayeuxServer.SubscriptionListener)listener, session, this, message);
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    private void notifySubscribed(SubscriptionListener listener, ServerSession session, ServerChannel channel, ServerMessage message) {
        try {
            listener.subscribed(session, channel, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifySubscribed(BayeuxServer.SubscriptionListener listener, ServerSession session, ServerChannel channel, ServerMessage message) {
        try {
            listener.subscribed(session, channel, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    @Override
    public boolean unsubscribe(ServerSession session) {
        return unsubscribe((ServerSessionImpl)session, null);
    }

    protected boolean unsubscribe(ServerSessionImpl session, ServerMessage message) {
        // The unsubscription may arrive when the session
        // is already disconnected; unsubscribe in any case

        // Subscriptions to service channels are allowed but
        // are a no-operation, so be symmetric here
        if (isService()) {
            return true;
        }
        if (isMeta()) {
            return false;
        }

        if (_subscribers.remove(session)) {
            session.unsubscribedFrom(this);
            for (ServerChannelListener listener : _listeners) {
                if (listener instanceof SubscriptionListener) {
                    notifyUnsubscribed((SubscriptionListener)listener, session, this, message);
                }
            }
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners()) {
                if (listener instanceof BayeuxServer.SubscriptionListener) {
                    notifyUnsubscribed((BayeuxServer.SubscriptionListener)listener, session, this, message);
                }
            }
        }

        return true;
    }

    private void notifyUnsubscribed(BayeuxServer.SubscriptionListener listener, ServerSession session, ServerChannel channel, ServerMessage message) {
        try {
            listener.unsubscribed(session, channel, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifyUnsubscribed(SubscriptionListener listener, ServerSession session, ServerChannel channel, ServerMessage message) {
        try {
            listener.unsubscribed(session, channel, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    @Override
    public Set<ServerSession> getSubscribers() {
        return Collections.unmodifiableSet(subscribers());
    }

    public Set<ServerSession> subscribers() {
        return _subscribers;
    }

    @Override
    public boolean isBroadcast() {
        return !isMeta() && !isService();
    }

    @Override
    public boolean isDeepWild() {
        return _id.isDeepWild();
    }

    @Override
    public boolean isLazy() {
        return _lazy;
    }

    @Override
    public boolean isPersistent() {
        return _persistent;
    }

    @Override
    public boolean isWild() {
        return _id.isWild();
    }

    @Override
    public void setLazy(boolean lazy) {
        _lazy = lazy;
        if (!lazy) {
            _lazyTimeout = -1;
        }
    }

    @Override
    public long getLazyTimeout() {
        return _lazyTimeout;
    }

    @Override
    public void setLazyTimeout(long lazyTimeout) {
        _lazyTimeout = lazyTimeout;
        setLazy(lazyTimeout > 0);
    }

    @Override
    public void setPersistent(boolean persistent) {
        resetSweeperPasses();
        _persistent = persistent;
    }

    @Override
    public void addListener(ServerChannelListener listener) {
        resetSweeperPasses();
        _listeners.add(listener);
    }

    @Override
    public boolean isBroadcastToPublisher() {
        return _broadcastToPublisher;
    }

    @Override
    public void setBroadcastToPublisher(boolean broadcastToPublisher) {
        _broadcastToPublisher = broadcastToPublisher;
    }

    @Override
    public void removeListener(ServerChannelListener listener) {
        _listeners.remove(listener);
    }

    @Override
    public List<ServerChannelListener> getListeners() {
        return Collections.unmodifiableList(listeners());
    }

    protected List<ServerChannelListener> listeners() {
        return _listeners;
    }

    @Override
    public ChannelId getChannelId() {
        return _id;
    }

    @Override
    public String getId() {
        return _id.getId();
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
    public void publish(Session from, ServerMessage.Mutable mutable, Promise<Boolean> promise) {
        if (isWild()) {
            throw new IllegalStateException("Wild publish");
        }

        mutable.setChannel(getId());

        ServerSessionImpl session = null;
        if (from instanceof ServerSessionImpl) {
            session = (ServerSessionImpl)from;
        } else if (from instanceof LocalSession) {
            session = (ServerSessionImpl)((LocalSession)from).getServerSession();
        }

        _bayeux.publish(session, this, mutable, false, promise);
    }

    @Override
    public void publish(Session from, Object data, Promise<Boolean> promise) {
        ServerMessage.Mutable mutable = _bayeux.newMessage();
        mutable.setData(data);
        publish(from, mutable, promise);
    }

    protected void sweep() {
        waitForInitialized();

        for (ServerSession session : _subscribers) {
            if (!session.isHandshook()) {
                unsubscribe(session);
            }
        }

        if (isMeta() || isPersistent()) {
            return;
        }

        if (!_subscribers.isEmpty()) {
            return;
        }

        if (!_authorizers.isEmpty()) {
            return;
        }

        for (ServerChannelListener listener : _listeners) {
            if (!(listener instanceof ServerChannelListener.Weak)) {
                return;
            }
        }

        if (_sweeperPasses.incrementAndGet() < 3) {
            return;
        }

        remove();
    }

    @Override
    public void remove() {
        if (_bayeux.removeServerChannel(this)) {
            for (ServerSession subscriber : _subscribers) {
                ((ServerSessionImpl)subscriber).unsubscribedFrom(this);
            }
            _subscribers.clear();
        }

        _listeners.clear();
    }

    @Override
    public void setAttribute(String name, Object value) {
        _attributes.setAttribute(name, value);
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
    public void addAuthorizer(Authorizer authorizer) {
        _authorizers.add(authorizer);
    }

    @Override
    public void removeAuthorizer(Authorizer authorizer) {
        _authorizers.remove(authorizer);
    }

    @Override
    public List<Authorizer> getAuthorizers() {
        return Collections.unmodifiableList(authorizers());
    }

    protected List<Authorizer> authorizers() {
        return _authorizers;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        List<Object> children = new ArrayList<>();

        children.add(childrenForDump("authorizers", authorizers()));
        children.add(childrenForDump("listeners", listeners()));
        children.add(childrenForDump("subscribers", subscribers()));

        Dumpable.dumpObjects(out, indent, this, children.toArray());
    }

    private Object childrenForDump(String name, Collection<?> collection) {
        if (_bayeux.isDetailedDump()) {
            return new DumpableCollection(name, collection);
        } else {
            return name + " size=" + collection.size();
        }
    }

    @Override
    public String toString() {
        return _id.toString();
    }
}
