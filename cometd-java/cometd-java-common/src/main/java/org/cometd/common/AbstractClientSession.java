package org.cometd.common;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.SessionChannel.SubscriberListener;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.log.Log;

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
    private final List<AbstractSessionChannel> _wild = new CopyOnWriteArrayList<AbstractSessionChannel>();
    private final AtomicInteger _idGen = new AtomicInteger(0);

    protected AbstractClientSession()
    {
    }

    protected int newMessageId()
    {
        return _idGen.incrementAndGet();
    }

    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
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

    /* ------------------------------------------------------------ */
    protected abstract ChannelId newChannelId(String channelId);

    /* ------------------------------------------------------------ */
    protected abstract AbstractSessionChannel newChannel(ChannelId channelId);

    /* ------------------------------------------------------------ */
    public SessionChannel getChannel(String channelId)
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
        if (channel.isWild())
            _wild.add(channel);
        return channel;
    }

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
    public void endBatch()
    {
        if (_batch.decrementAndGet()==0)
        {
            sendBatch();
        }
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
    /** Receive a message (from the server)
     * <p>
     * This method calls the receive extensions, the ClientSessionListeners and then
     * the subscribed MessageListeners.
     * @param message the message to receive.
     */
    public void receive(final Message message, final Message.Mutable mutable)
    {
        final String id=message.getChannel();
        final AbstractSessionChannel channel=id==null?null:(AbstractSessionChannel)getChannel(id);
        final ChannelId channelId=channel==null?null:channel.getChannelId();

        if (channel!=null && channel._handler!=null)
            channel._handler.handle(this,mutable);

        if (!extendRcv(mutable))
            return;

        if (message.isMeta())
        {
            String error = (String)message.get(Message.ERROR_FIELD);
            boolean successful = message.isSuccessful();

            if (channelId!=null)
            {
                for (AbstractSessionChannel wild : _wild)
                {
                    if (wild._id.matches(channelId))
                    {
                        for (SessionChannel.SessionChannelListener listener : wild._listeners)
                        {
                            try
                            {
                                if (listener instanceof SessionChannel.MetaChannelListener)
                                    ((SessionChannel.MetaChannelListener)listener).onMetaMessage(channel,message,successful,error);
                                if (listener instanceof SessionChannel.MessageListener)
                                    ((SessionChannel.MessageListener)listener).onMessage(this,message);
                            }
                            catch(Exception e)
                            {
                                Log.warn(e);
                            }
                        }
                    }
                }

                for (SessionChannel.SessionChannelListener listener : channel._listeners)
                {
                    try
                    {
                        if (listener instanceof SessionChannel.MetaChannelListener)
                            ((SessionChannel.MetaChannelListener)listener).onMetaMessage(channel,message,message.isSuccessful(),error);
                    }
                    catch(Exception e)
                    {
                        Log.warn(e);
                    }
                }
            }
        }
        else
        {
            if (channelId!=null)
            {
                for (AbstractSessionChannel wild : _wild)
                {
                    try
                    {
                        if (wild._id.matches(channel._id))
                        {
                            for (SessionChannel.SessionChannelListener listener : wild._listeners)
                            {
                                if (listener instanceof SessionChannel.MessageListener)
                                    ((SessionChannel.MessageListener)listener).onMessage(this,message);
                            }
                        }
                    }
                    catch(Exception e)
                    {
                        Log.warn(e);
                    }
                }
            }
        }

        if (channel!=null && (channel.isMeta() || message.getData()!=null))
        {
            for (SubscriberListener listener : channel._subscriptions)
            {
                try
                {
                    listener.onMessage(channel,message);
                }
                catch(Exception e)
                {
                    Log.warn(e);
                }
            }
        }

        if (Channel.META_DISCONNECT.equals(id) && message.isSuccessful())
            doDisconnected();
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

    /* ------------------------------------------------------------ */
    protected abstract void doDisconnected();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected interface Handler
    {
        void handle(AbstractClientSession session, Message.Mutable mutable);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** A SessionChannel scoped to this LocalChannel
     */
    protected abstract static class AbstractSessionChannel implements SessionChannel
    {
        protected final ChannelId _id;
        protected CopyOnWriteArrayList<SubscriberListener> _subscriptions = new CopyOnWriteArrayList<SubscriberListener>();
        protected CopyOnWriteArrayList<SessionChannelListener> _listeners = new CopyOnWriteArrayList<SessionChannelListener>();
        protected Handler _handler;

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
        public void addListener(SessionChannelListener listener)
        {
            _listeners.add(listener);
        }

        /* ------------------------------------------------------------ */
        public void removeListener(SessionChannelListener listener)
        {
            _listeners.remove(listener);
        }

        /* ------------------------------------------------------------ */
        protected abstract void sendSubscribe();

        /* ------------------------------------------------------------ */
        protected abstract void sendUnSubscribe();

        /* ------------------------------------------------------------ */
        public void subscribe(SubscriberListener listener)
        {
            _subscriptions.add(listener);
            if (_subscriptions.size()==1)
                sendSubscribe();
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe(SubscriberListener listener)
        {
            if (_subscriptions.remove(listener) && _subscriptions.size()==0)
            {
                sendUnSubscribe();
            }
        }

        /* ------------------------------------------------------------ */
        public void unsubscribe()
        {
            for (SubscriberListener listener : _subscriptions)
                unsubscribe(listener);
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

        /* ------------------------------------------------------------ */
        public void setHandler(Handler handler)
        {
            _handler=handler;
        }

        protected void dump(StringBuilder b,String indent)
        {
            b.append(toString());
            b.append('\n');

            for (SessionChannelListener child : _listeners)
            {
                b.append(indent);
                b.append(" +-");
                b.append(child);
                b.append('\n');
            }
            for (SubscriberListener child : _subscriptions)
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
