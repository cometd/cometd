package org.cometd.server;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.ChannelId;

public class ServerChannelImpl implements ServerChannel
{
    private final BayeuxServerImpl _bayeux;
    private final ServerChannelImpl _parent;
    private final ChannelId _id;
    private final Set<ServerSessionImpl> _subscribers = new CopyOnWriteArraySet<ServerSessionImpl>();
    private final List<ServerChannelListener> _listeners = new CopyOnWriteArrayList<ServerChannelListener>();
    private final ConcurrentMap<String,ServerChannelImpl> _children=new ConcurrentHashMap<String,ServerChannelImpl>();
    private final boolean _meta;
    private final boolean _broadcast;
    private final boolean _service;
    private boolean _lazy;
    private boolean _persistent;
    private ServerChannelImpl _wild;
    private ServerChannelImpl _deepWild;

    /* ------------------------------------------------------------ */
    ServerChannelImpl(BayeuxServerImpl bayeux, ServerChannelImpl parent, ChannelId id)
    {
        _bayeux=bayeux;
        _parent=parent;
        _id=id;
        _meta=_id.isMeta();
        _service=_id.isService();
        _broadcast=!isMeta()&&!isService();
        setPersistent(!_broadcast);
    }

    /* ------------------------------------------------------------ */
    void subscribe(ServerSessionImpl session)
    {
        _subscribers.add(session);
        for (ServerChannelListener listener : _listeners)
            if (listener instanceof SubscriptionListener)
                ((SubscriptionListener)listener).subscribed(session,this);
        for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
            if (listener instanceof BayeuxServer.SubscriptionListener)
                ((BayeuxServer.SubscriptionListener)listener).subscribed(session,this);
    }

    /* ------------------------------------------------------------ */
    void unsubscribe(ServerSessionImpl session)
    {
        if(_subscribers.remove(session))
        {
            for (ServerChannelListener listener : _listeners)
                if (listener instanceof SubscriptionListener)
                    ((SubscriptionListener)listener).unsubscribed(session,this);
            for (BayeuxServer.BayeuxServerListener listener : _bayeux.getListeners())
                if (listener instanceof BayeuxServer.SubscriptionListener)
                    ((BayeuxServer.SubscriptionListener)listener).unsubscribed(session,this);
            
            // TODO this is a race!
            if (!isPersistent() && _subscribers.size()==0 && _children.size()==0)
                remove();
        }
    }
    
    /* ------------------------------------------------------------ */
    public Set<? extends ServerSession> getSubscribers()
    {
        return _subscribers;
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
        _listeners.add((ServerChannelListener)listener);
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
    public ServerChannelImpl getChild(ChannelId id,boolean create)
    {
        if (!_id.isParentOf(id))
        {
            if (create)                
                throw new IllegalArgumentException(_id + " not parent of " + id);
            return null;
        }
    
        String next=id.getSegment(_id.depth());
        ServerChannelImpl child = _children.get(next);
        
        if (child==null)
        {
            if (!create)
                return null;
            
            child=new ServerChannelImpl(_bayeux,this,new ChannelId((_id.depth()==0?"/":(_id.toString() + "/")) + next));

            ServerChannelImpl old=_children.putIfAbsent(next,child);
            if (old==null)
            {
                if (ChannelId.WILD.equals(next))
                    _wild=child;
                else if (ChannelId.DEEPWILD.equals(next))
                    _deepWild=child;
                _bayeux.addServerChannel(child);
            }
            else
                child=old;
        }
        
        if ((id.depth() - _id.depth()) > 1)
            return child.getChild(id,create);
        return child;
    }
    
    /* ------------------------------------------------------------ */
    public void publish(Session from, ServerMessage msg)
    {
        if (isWild())
            throw new IllegalStateException("Wild publish");
        ServerMessage.Mutable mutable = msg.asMutable();
        if(_bayeux.extendSend(null,mutable))
            _bayeux.root().doPublish((ServerSessionImpl)from,this,mutable);
    }
    
    /* ------------------------------------------------------------ */
    public void publish(Session from, Object data, Object id)
    {
        if (isWild())
            throw new IllegalStateException("Wild publish");
        
        ServerMessage.Mutable mutable = _bayeux.newMessage();
        mutable.setChannel(getId());
        if(from!=null)
            mutable.setClientId(from.getId());
        mutable.setData(data);
        mutable.setId(id);
        
        if(_bayeux.extendSend(null,mutable))
        {
            ServerSessionImpl session=(ServerSessionImpl)((from instanceof LocalSession)?(((LocalSession)from).getServerSession()):((ServerSession)from));
            _bayeux.root().doPublish(session,this,mutable);
        }
    }

    /* ------------------------------------------------------------ */
    void doPublish(ServerSessionImpl from, ServerChannelImpl to, final ServerMessage.Mutable mutable)
    {
        // Deeply apply all the listeners, so that they may perform all
        // mutable changes before any deliveries take place.
        // this means that if there is a subscriber at /foo/** and a mutating
        // listener at /foo/bar/wibble, then the /foo/** subscribe will
        // see the mutated message.
        
        int tail=to._id.depth() - _id.depth();
        final ServerChannelImpl wild=_wild;
        final ServerChannelImpl deepwild=_deepWild;

        switch(tail)
        {
            case 0:
                if (isLazy())
                    mutable.setLazy(true);
                for (ServerChannelListener listener : _listeners)
                    if (listener instanceof MessageListener)
                        if (!((MessageListener)listener).onMessage(from,to,mutable))
                            return;
            
                _bayeux.root().doSubscribers(from,to._id,mutable);
                if (isMeta())
                    for (ServerChannelListener listener : _listeners)
                        if (listener instanceof BayeuxServerImpl.HandlerListener)
                            ((BayeuxServerImpl.HandlerListener)listener).onMessage(from,mutable);
                break;

            case 1:
                if (wild != null)
                {
                    if (wild.isLazy())
                        mutable.setLazy(true);
                    
                    for (ServerChannelListener listener : wild._listeners)
                        if (listener instanceof MessageListener)
                            if (!((MessageListener)listener).onMessage(from,to,mutable))
                                return;
                }
                // fall through to default
                
            default:
                if (deepwild != null)
                {
                    if (deepwild.isLazy())
                        mutable.setLazy(true);
                    for (ServerChannelListener listener : deepwild._listeners)
                        if (listener instanceof MessageListener)
                            if (!((MessageListener)listener).onMessage(from,to,mutable))
                                return;
                }

                String next=to._id.getSegment(_id.depth());
                ServerChannelImpl channel=_children.get(next);
                if (channel != null)
                    channel.doPublish(from,to,mutable);
        }
    }
    
    /* ------------------------------------------------------------ */
    void doSubscribers(ServerSessionImpl from, ChannelId to, final ServerMessage.Mutable mutable)
    {
        final ServerMessage message = mutable.asImmutable();
        
        int tail=to.depth() - _id.depth();
        final ServerChannelImpl wild=_wild;
        final ServerChannelImpl deepwild=_deepWild;

        switch(tail)
        {
            case 0:
            
                for (ServerSessionImpl session : _subscribers)
                    session.doDeliver(from,message);
                break;

            case 1:
                if (wild != null)
                {
                    for (ServerSessionImpl session : wild._subscribers)
                        session.doDeliver(from,message);
                }
                // fall through to default
                   
            default:
                if (deepwild != null)
                {
                    for (ServerSessionImpl session : deepwild._subscribers)
                        session.doDeliver(from,message);
                }
                
                String next=to.getSegment(_id.depth());
                ServerChannelImpl channel=_children.get(next);
                if (channel != null)
                    channel.doSubscribers(from,to,mutable);
        }
    }

    public void remove()
    {
        for (ServerChannelImpl child : _children.values())
            child.remove();
        
        if (_bayeux.removeServerChannel(this))
        {
            if (isDeepWild() && _parent._deepWild==this)
                _parent._deepWild=null;
            else if (isWild() && _parent._wild==this)
                _parent._wild=null;
            
            if (_parent._children.remove(_id.getSegment(_id.depth()-1),this))
            {
                for (ServerSessionImpl session : _subscribers)
                    unsubscribe(session);
            }
        }
        
    }

    /* ------------------------------------------------------------ */
    protected void dump(StringBuilder b,String indent)
    {
        b.append(toString());
        b.append(isLazy()?" lazy":"");
        b.append('\n');
        
        int leaves=_children.size()+_subscribers.size()+_listeners.size();
        int i=0;
        for (ServerChannelImpl child : _children.values())
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

