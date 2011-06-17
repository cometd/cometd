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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.AttributesMap;

public class ServerChannelImpl implements ServerChannel, ConfigurableServerChannel
{
    private final BayeuxServerImpl _bayeux;
    private final ChannelId _id;
    private final AttributesMap _attributes = new AttributesMap();
    private final Set<ServerSession> _subscribers = new CopyOnWriteArraySet<ServerSession>();
    private final List<ServerChannelListener> _listeners = new CopyOnWriteArrayList<ServerChannelListener>();
    private final List<Authorizer> _authorizers = new CopyOnWriteArrayList<Authorizer>();
    private final CountDownLatch _initialized;
    private boolean _lazy;
    private boolean _persistent;
    private volatile int _sweeperPasses = 0;

    /* ------------------------------------------------------------ */
    protected ServerChannelImpl(BayeuxServerImpl bayeux, ChannelId id)
    {
        _bayeux=bayeux;
        _id=id;
        _initialized=new CountDownLatch(1);
        setPersistent(!isBroadcast());
    }

    /* ------------------------------------------------------------ */
    /* wait for initialised call.
     * wait for bayeux max interval for the channel to be initialised,
     * which means waiting for addChild to finish calling bayeux.addChannel,
     * which calls all the listeners.
     *
     */
    void waitForInitialized()
    {
        try
        {
            if (!_initialized.await(5,TimeUnit.SECONDS))
                throw new IllegalStateException("Not Initialized: "+this);
        }
        catch (InterruptedException x)
        {
            throw new IllegalStateException("Initialization interrupted: "+this, x);
        }
    }

    /* ------------------------------------------------------------ */
    void initialized()
    {
        _initialized.countDown();
    }

    public boolean subscribe(ServerSession session)
    {
        if (!session.isHandshook())
            return false;

        // Maintain backward compatibility by allowing subscriptions
        // to service channels to be a no-operation, but succeed
        if (isService())
            return true;
        if (isMeta())
            return false;

        return subscribe((ServerSessionImpl)session);
    }

    private boolean subscribe(ServerSessionImpl session)
    {
        if (_subscribers.add(session))
        {
            session.subscribedTo(this);
            for (ServerChannelListener listener : _listeners)
                if (listener instanceof SubscriptionListener)
                    notifySubscribed((SubscriptionListener)listener, session, this);
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
                if (listener instanceof BayeuxServer.SubscriptionListener)
                    notifySubscribed((BayeuxServer.SubscriptionListener)listener, session, this);
        }
        _sweeperPasses = 0;
        return true;
    }

    private void notifySubscribed(SubscriptionListener listener, ServerSession session, ServerChannel channel)
    {
        try
        {
            listener.subscribed(session, channel);
        }
        catch (Exception x)
        {
            _bayeux.getLogger().info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifySubscribed(BayeuxServer.SubscriptionListener listener, ServerSession session, ServerChannel channel)
    {
        try
        {
            listener.subscribed(session, channel);
        }
        catch (Exception x)
        {
            _bayeux.getLogger().info("Exception while invoking listener " + listener, x);
        }
    }

    public boolean unsubscribe(ServerSession session)
    {
        // The unsubscription may arrive when the session
        // is already disconnected; unsubscribe in any case

        // Subscriptions to service channels are allowed but
        // are a no-operation, so be symmetric here
        if (isService())
            return true;
        if (isMeta())
            return false;

        return unsubscribe((ServerSessionImpl)session);
    }

    private boolean unsubscribe(ServerSessionImpl session)
    {
        if(_subscribers.remove(session))
        {
            session.unsubscribedFrom(this);
            for (ServerChannelListener listener : _listeners)
                if (listener instanceof SubscriptionListener)
                    notifyUnsubscribed((SubscriptionListener)listener, session, this);
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
                if (listener instanceof BayeuxServer.SubscriptionListener)
                    notifyUnsubscribed((BayeuxServer.SubscriptionListener)listener, session, this);
        }
        return true;
    }

    private void notifyUnsubscribed(BayeuxServer.SubscriptionListener listener, ServerSession session, ServerChannel channel)
    {
        try
        {
            listener.unsubscribed(session, channel);
        }
        catch (Exception x)
        {
            _bayeux.getLogger().info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifyUnsubscribed(SubscriptionListener listener, ServerSession session, ServerChannel channel)
    {
        try
        {
            listener.unsubscribed(session, channel);
        }
        catch (Exception x)
        {
            _bayeux.getLogger().info("Exception while invoking listener " + listener, x);
        }
    }

    /* ------------------------------------------------------------ */
    public Set<ServerSession> getSubscribers()
    {
        return Collections.unmodifiableSet(_subscribers);
    }

    /* ------------------------------------------------------------ */
    public boolean isBroadcast()
    {
        return !isMeta() && !isService();
    }

    /* ------------------------------------------------------------ */
    public boolean isDeepWild()
    {
        return _id.isDeepWild();
    }

    /* ------------------------------------------------------------ */
    public boolean isLazy()
    {
        return _lazy;
    }

    /* ------------------------------------------------------------ */
    public boolean isPersistent()
    {
        return _persistent;
    }

    /* ------------------------------------------------------------ */
    public boolean isWild()
    {
        return _id.isWild();
    }

    /* ------------------------------------------------------------ */
    public void setLazy(boolean lazy)
    {
        _lazy=lazy;
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    public void addListener(ServerChannelListener listener)
    {
        _listeners.add(listener);
        _sweeperPasses = 0;
    }

    /* ------------------------------------------------------------ */
    public void removeListener(ServerChannelListener listener)
    {
        _listeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    public List<ServerChannelListener> getListeners()
    {
        return Collections.unmodifiableList(_listeners);
    }

    /* ------------------------------------------------------------ */
    public ChannelId getChannelId()
    {
        return _id;
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return _id.toString();
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
    public void publish(Session from, ServerMessage.Mutable mutable)
    {
        if (isWild())
            throw new IllegalStateException("Wild publish");

        ServerSessionImpl session=(from instanceof ServerSessionImpl)
          ?(ServerSessionImpl)from
          :((from instanceof LocalSession)?(ServerSessionImpl)((LocalSession)from).getServerSession():null);

        // Do not leak the clientId to other subscribers
        // as we are now "sending" this message
        mutable.setClientId(null);

        if(_bayeux.extendSend(session,null,mutable))
            _bayeux.doPublish(session,this,mutable);
    }

    /* ------------------------------------------------------------ */
    public void publish(Session from, Object data, String id)
    {
        ServerMessage.Mutable mutable = _bayeux.newMessage();
        mutable.setChannel(getId());
        if(from!=null)
            mutable.setClientId(from.getId());
        mutable.setData(data);
        mutable.setId(id);
        publish(from,mutable);
    }

    /* ------------------------------------------------------------ */
    protected void sweep()
    {
        for (ServerSession session : _subscribers)
        {
            if (!session.isHandshook())
                unsubscribe((ServerSessionImpl)session);
        }

        if (isPersistent())
            return;

        if (_subscribers.size() > 0 || _listeners.size() > 0)
            return;

        if (isWild())
        {
            // Wild, check if has authorizers that can match other channels
            if (_authorizers.size() > 0)
                return;
        }
        else
        {
            // Not wild, then check if it has children
            for (ServerChannel channel : _bayeux.getChannels())
                if (_id.isParentOf(channel.getChannelId()))
                    return;
        }

        if (++_sweeperPasses < 3)
            return;

        remove();
    }

    /* ------------------------------------------------------------ */
    public void remove()
    {
        for (ServerChannelImpl child : _bayeux.getChannelChildren(_id))
            child.remove();

        if (_bayeux.removeServerChannel(this))
        {
            for (ServerSession subscriber: _subscribers)
                ((ServerSessionImpl)subscriber).unsubscribedFrom(this);
            _subscribers.clear();
        }

        _listeners.clear();
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

    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append(isLazy()?" lazy":"");
        b.append('\n');

        List<ServerChannelImpl> children =_bayeux.getChannelChildren(_id);
        int leaves=children.size()+_subscribers.size()+_listeners.size();
        int i=0;
        for (ServerChannelImpl child : children)
        {
            b.append(indent);
            b.append(" +-");
            child.dump(b,indent+((++i==leaves)?"   ":" | "));
        }
        for (ServerSession child : _subscribers)
        {
            b.append(indent);
            b.append(" +-");
            ((ServerSessionImpl)child).dump(b,indent+((++i==leaves)?"   ":" | "));
        }
        for (ServerChannelListener child : _listeners)
        {
            b.append(indent);
            b.append(" +-");
            b.append(child);
            b.append('\n');
        }
    }

    /* ------------------------------------------------------------ */
    public void addAuthorizer(Authorizer authorizer)
    {
        _authorizers.add(authorizer);
    }

    /* ------------------------------------------------------------ */
    public void removeAuthorizer(Authorizer authorizer)
    {
        _authorizers.remove(authorizer);
    }

    /* ------------------------------------------------------------ */
    public List<Authorizer> getAuthorizers()
    {
        return Collections.unmodifiableList(_authorizers);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _id.toString();
    }
}

