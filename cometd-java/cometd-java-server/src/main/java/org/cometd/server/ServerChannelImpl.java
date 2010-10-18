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
    private final Set<ServerSessionImpl> _subscribers = new CopyOnWriteArraySet<ServerSessionImpl>();
    private final List<ServerChannelListener> _listeners = new CopyOnWriteArrayList<ServerChannelListener>();
    private final boolean _meta;
    private final boolean _broadcast;
    private final boolean _service;
    private final CountDownLatch _initialized;
    private boolean _lazy;
    private boolean _persistent;
    private volatile int _used=0;

    /* ------------------------------------------------------------ */
    protected ServerChannelImpl(BayeuxServerImpl bayeux, ChannelId id)
    {
        _bayeux=bayeux;
        _id=id;
        _meta=_id.isMeta();
        _service=_id.isService();
        _broadcast=!isMeta()&&!isService();
        _initialized=new CountDownLatch(1);
        setPersistent(!_broadcast);
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
        catch(InterruptedException e)
        {
            throw new IllegalStateException("Initizlization interrupted: "+this);
        }
    }

    /* ------------------------------------------------------------ */
    void initialized()
    {
        _initialized.countDown();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param session
     * @return true if the subscribe succeeded.
     */
    protected boolean subscribe(ServerSessionImpl session)
    {
        if (!session.isHandshook())
            return false;
        if (_subscribers.add(session))
        {
            session.subscribedTo(this);
            for (ServerChannelListener listener : _listeners)
                if (listener instanceof SubscriptionListener)
                    ((SubscriptionListener)listener).subscribed(session,this);
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
                if (listener instanceof BayeuxServer.SubscriptionListener)
                    ((BayeuxServer.SubscriptionListener)listener).subscribed(session,this);
        }
        _used=0;
        return true;
    }

    /* ------------------------------------------------------------ */
    protected void unsubscribe(ServerSessionImpl session)
    {
        if(_subscribers.remove(session))
        {
            session.unsubscribedTo(this);
            for (ServerChannelListener listener : _listeners)
                if (listener instanceof SubscriptionListener)
                    ((SubscriptionListener)listener).unsubscribed(session,this);
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
                if (listener instanceof BayeuxServer.SubscriptionListener)
                    ((BayeuxServer.SubscriptionListener)listener).unsubscribed(session,this);
        }
    }

    /* ------------------------------------------------------------ */
    public List<ServerChannelListener> getListeners()
    {
        return _listeners;
    }

    /* ------------------------------------------------------------ */
    public Set<? extends ServerSession> getSubscribers()
    {
        return Collections.unmodifiableSet(_subscribers);
    }

    /* ------------------------------------------------------------ */
    public boolean isBroadcast()
    {
        return _broadcast;
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
        return _meta;
    }

    /* ------------------------------------------------------------ */
    public boolean isService()
    {
        return _service;
    }

    /* ------------------------------------------------------------ */
    public void removeListener(ServerChannelListener listener)
    {
        _listeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    public void publish(Session from, ServerMessage.Mutable mutable)
    {
        if (isWild())
            throw new IllegalStateException("Wild publish");

        ServerSessionImpl session=(from instanceof ServerSessionImpl)
          ?(ServerSessionImpl)from
          :((from instanceof LocalSession)?(ServerSessionImpl)((LocalSession)from).getServerSession():null);

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
    protected void doSweep(int children)
    {
        for (ServerSessionImpl session : _subscribers)
        {
            if (!session.isHandshook())
                unsubscribe(session);
        }

        if (!isPersistent() && _subscribers.size()==0 && _listeners.size()==0 && children==0 && ++_used>2)
            remove();
    }

    /* ------------------------------------------------------------ */
    public void remove()
    {
        for (ServerChannelImpl child : _bayeux.getChannelChildren(_id))
            child.remove();

        if (_bayeux.removeServerChannel(this))
        {
            for (ServerSessionImpl subscriber: _subscribers)
                subscriber.unsubscribedTo(this);
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
        for (ServerSessionImpl child : _subscribers)
        {
            b.append(indent);
            b.append(" +-");
            child.dump(b,indent+((++i==leaves)?"   ":" | "));
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
    @Override
    public String toString()
    {
        return _id.toString();
    }
}

