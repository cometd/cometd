package org.cometd.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel.Initializer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerChannel.MessageListener;
import org.cometd.bayeux.server.ServerChannel.ServerChannelListener;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.ChannelId;
import org.cometd.server.transport.JSONPTransport;
import org.cometd.server.transport.JSONTransport;
import org.cometd.server.transport.WebSocketTransport;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout;


/* ------------------------------------------------------------ */
/**
 *
 * Options to configure the server are: <dl>
 * <tt>tickIntervalMs</tt><td>The time in milliseconds between ticks to check for timeouts etc</td>
 * <tt>sweepIntervalMs</tt><td>The time in milliseconds between sweeps of channels to remove
 * invalid subscribers and non-persistent channels</td>
 * </dl>
 */
public class BayeuxServerImpl extends AbstractLifeCycle implements BayeuxServer
{
    private final Logger _logger;
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<BayeuxServerListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<String, ServerSessionImpl>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<String, ServerChannelImpl>();
    private final ConcurrentMap<String, Transport> _transports = new ConcurrentHashMap<String, Transport>();
    private final List<String> _allowedTransports = new CopyOnWriteArrayList<String>();
    private final ThreadLocal<AbstractServerTransport> _currentTransport = new ThreadLocal<AbstractServerTransport>();
    private final Map<String,Object> _options = new TreeMap<String, Object>();
    private final Timeout _timeout = new Timeout();

    private SecurityPolicy _policy=new DefaultSecurityPolicy();
    private Timer _timer = new Timer();
    private Object _handshakeAdvice=new JSON.Literal("{\"reconnect\":\"handshake\",\"interval\":500}");

    /* ------------------------------------------------------------ */
    public BayeuxServerImpl()
    {
        _logger=Log.getLogger("bayeux@"+hashCode());

        createIfAbsent(Channel.META_HANDSHAKE);
        createIfAbsent(Channel.META_CONNECT);
        createIfAbsent(Channel.META_SUBSCRIBE);
        createIfAbsent(Channel.META_UNSUBSCRIBE);
        createIfAbsent(Channel.META_DISCONNECT);
        ((ServerChannelImpl)getChannel(Channel.META_HANDSHAKE)).addListener(new HandshakeHandler());
        ((ServerChannelImpl)getChannel(Channel.META_CONNECT)).addListener(new ConnectHandler());
        ((ServerChannelImpl)getChannel(Channel.META_SUBSCRIBE)).addListener(new SubscribeHandler());
        ((ServerChannelImpl)getChannel(Channel.META_UNSUBSCRIBE)).addListener(new UnsubscribeHandler());
        ((ServerChannelImpl)getChannel(Channel.META_DISCONNECT)).addListener(new DisconnectHandler());


        setOption("tickIntervalMs","97");
        setOption("sweepIntervalMs","997");
    }

    /* ------------------------------------------------------------ */
    public BayeuxServerImpl(boolean initializeDefaultTransports)
    {
        this();
        if (initializeDefaultTransports)
            initializeDefaultTransports();
    }

    /* ------------------------------------------------------------ */
    /** Initialize the default transports.
     * This method creates a {@link WebSocketTransport}, a {@link JSONTransport}
     * and a {@link JSONPTransport} and calls {@link BayeuxServerImpl#setAllowedTransports(String...)}.
     */
    public void initializeDefaultTransports()
    {
        addTransport(new WebSocketTransport(this));
        addTransport(new JSONTransport(this));
        addTransport(new JSONPTransport(this));
        setAllowedTransports(WebSocketTransport.NAME,JSONTransport.NAME,JSONPTransport.NAME);
    }

    /* ------------------------------------------------------------ */
    public Logger getLogger()
    {
        return _logger;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        _timer=new Timer("BayeuxServer@" +hashCode(),true);
        long tick_interval = getLongOptions("tickIntervalMs",-1);
        if (tick_interval>0)
            _timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    _timeout.tick(System.currentTimeMillis());
                }
            },tick_interval,tick_interval);

        long sweep_interval = getLongOptions("sweepIntervalMs",-1);
        if (sweep_interval>0)
            _timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    doSweep();

                    final long now=System.currentTimeMillis();
                    for (ServerSessionImpl session : _sessions.values())
                        session.sweep(now);
                }
            },sweep_interval,sweep_interval);
    }


    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _timer.cancel();
        _timer=null;
    }

    /* ------------------------------------------------------------ */
    public void startTimeout(Timeout.Task task, long interval)
    {
        _timeout.schedule(task,interval);
    }

    /* ------------------------------------------------------------ */
    public void cancelTimeout(Timeout.Task task)
    {
        task.cancel();
    }

    /* ------------------------------------------------------------ */
    public ChannelId newChannelId(String id)
    {
        ServerChannelImpl channel = _channels.get(id);
        if (channel!=null)
            return channel.getChannelId();
        return new ChannelId(id);
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getOptions()
    {
        return _options;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#getOption(java.lang.String)
     */
    public Object getOption(String qualifiedName)
    {
        return _options.get(qualifiedName);
    }

    /* ------------------------------------------------------------ */
    /** Get an option value as a long
     * @param name
     * @param dft The default value
     * @return long value
     */
    protected long getLongOptions(String name,long dft)
    {
        Object val=getOption(name);
        if (val instanceof Long)
            return ((Long)val).longValue();
        if (val!=null)
            return Long.valueOf(val.toString());
        return dft;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#getOptionNames()
     */
    public Set<String> getOptionNames()
    {
        return _options.keySet();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Bayeux#setOption(java.lang.String, java.lang.Object)
     */
    public void setOption(String qualifiedName, Object value)
    {
        _options.put(qualifiedName,value);
    }

    /* ------------------------------------------------------------ */
    public int randomInt()
    {
        return _random.nextInt();
    }

    /* ------------------------------------------------------------ */
    public int randomInt(int n)
    {
        return _random.nextInt(n);
    }

    /* ------------------------------------------------------------ */
    public long randomLong()
    {
        return _random.nextLong();
    }

    /* ------------------------------------------------------------ */
    public void setCurrentTransport(AbstractServerTransport transport)
    {
        _currentTransport.set(transport);
    }

    /* ------------------------------------------------------------ */
    public ServerTransport getCurrentTransport()
    {
        return _currentTransport.get();
    }

    /* ------------------------------------------------------------ */
    public SecurityPolicy getSecurityPolicy()
    {
        return _policy;
    }

    /* ------------------------------------------------------------ */
    public boolean createIfAbsent(String channelId, ServerChannel.Initializer... initializers)
    {
        if (_channels.containsKey(channelId))
            return false;

        ChannelId id = new ChannelId(channelId);
        if (id.depth()>1)
            createIfAbsent(id.getParent());

        ServerChannelImpl proposed = new ServerChannelImpl(this,id);
        ServerChannelImpl channel = _channels.putIfAbsent(channelId,proposed);
        if (channel==null)
        {
            // My proposed channel was added to the map, so I'd better initialize it!
            channel=proposed;
            _logger.debug("added {}",channel);
            try
            {
                for (Initializer initializer : initializers)
                    initializer.configureChannel(channel);
                for (BayeuxServer.BayeuxServerListener listener : _listeners)
                {
                    if (listener instanceof ServerChannel.Initializer)
                        ((ServerChannel.Initializer)listener).configureChannel(channel);
                }
            }
            finally
            {
                channel.initialized();
            }

            for (BayeuxServer.BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.ChannelListener)
                    ((BayeuxServer.ChannelListener)listener).channelAdded(channel);
            }

            return true;
        }

        // somebody else added it before me, so wait until it is initialized
        channel.waitForInitialized();
        return false;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public ServerChannel getChannel(String channelId, boolean create)
    {
        ServerChannelImpl channel = _channels.get(channelId);
        if (channel==null && create)
        {
            createIfAbsent(channelId);
            channel = _channels.get(channelId);
        }
        return channel;
    }

    /* ------------------------------------------------------------ */
    public Collection<ServerSessionImpl> getSessions()
    {
        return Collections.unmodifiableCollection(_sessions.values());
    }

    /* ------------------------------------------------------------ */
    public ServerSession getSession(String clientId)
    {
        if (clientId==null)
            return null;
        return _sessions.get(clientId);
    }

    /* ------------------------------------------------------------ */
    protected void addServerSession(ServerSessionImpl session)
    {
        _sessions.put(session.getId(),session);
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.SessionListener)
                ((SessionListener)listener).sessionAdded(session);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param session
     * @param timedout
     * @return true if the session was removed and was connected
     */
    public boolean removeServerSession(ServerSession session,boolean timedout)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("remove "+session+(timedout?" timedout":""));

        ServerSessionImpl removed =_sessions.remove(session.getId());

        if(removed==session)
        {
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.SessionListener)
                    ((SessionListener)listener).sessionRemoved(session,timedout);
            }

            return ((ServerSessionImpl)session).removed(timedout);
        }
        else
            return false;
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl newServerSession()
    {
        return new ServerSessionImpl(this);
    }

    /* ------------------------------------------------------------ */
    protected ServerSessionImpl newServerSession(LocalSessionImpl local, String idHint)
    {
        return new ServerSessionImpl(this,local,idHint);
    }

    /* ------------------------------------------------------------ */
    public LocalSession newLocalSession(String idHint)
    {
        return new LocalSessionImpl(this,idHint);
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable newMessage()
    {
        return new ServerMessageImpl().asMutable();
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable newMessage(ServerMessage tocopy)
    {
        ServerMessage.Mutable mutable = new ServerMessageImpl().asMutable();
        for (String key : tocopy.keySet())
            mutable.put(key,tocopy.get(key));
        return mutable;
    }

    /* ------------------------------------------------------------ */
    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _policy=securityPolicy;
    }

    /* ------------------------------------------------------------ */
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    @Override
    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxServerListener listener)
    {
        if (!(listener instanceof BayeuxServerListener))
            throw new IllegalArgumentException("!BayeuxServerListener");
        _listeners.add((BayeuxServerListener)listener);
    }

    /* ------------------------------------------------------------ */
    public ServerChannel getChannel(String channelId)
    {
        return _channels.get(channelId);
    }

    /* ------------------------------------------------------------ */
    public List<ServerChannelImpl> getChannelChildren(ChannelId id)
    {
        ArrayList<ServerChannelImpl> children = new ArrayList<ServerChannelImpl>();
        for (ServerChannelImpl channel :_channels.values())
        {
            if (id.isParentOf(channel.getChannelId()))
                children.add(channel);
        }
        return children;
    }

    /* ------------------------------------------------------------ */
    public void removeListener(BayeuxServerListener listener)
    {
        _listeners.remove(listener);
    }

    /* ------------------------------------------------------------ */
    /** Extend and handle in incoming message.
     * @param session The session if known
     * @param message The message.
     * @return An unextended reply message
     */
    public ServerMessage handle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply=null;

        if (_logger.isDebugEnabled())
            _logger.debug(">  "+message+" "+session);

        if (!extendRecv(session,message) || session!=null && !session.extendRecv(message))
        {
            reply=createReply(message);
            reply.setSuccessful(false);
            reply.put(Message.ERROR_FIELD,"404::Message deleted");
        }
        else
        {
            if (_logger.isDebugEnabled())
                _logger.debug(">> "+message);
            String channelId=message.getChannel();

            ServerChannel channel=null;
            if (channelId!=null)
            {
                channel = getChannel(channelId);
                if (channel==null && _policy.canCreate(this,session,channelId,message))
                {
                    createIfAbsent(channelId);
                    channel = getChannel(channelId);
                }
            }

            if (channel==null)
            {
                reply = createReply(message);
                error(reply,channelId==null?"402::no channel":"403::Cannot create");
            }
            else if (channel.isMeta())
            {
                doPublish(session,(ServerChannelImpl)channel,message);
                reply = message.getAssociated().asMutable();
            }
            else if (_policy.canPublish(this,session,channel,message))
            {
                // This is a normal publish,

                // if this is not a local client, lets create a new message as we
                // don't trust what else was in their message.
                if (session!=null && session.isLocalSession() || channel.isService())
                {
                    message.setClientId(null);
                    channel.publish(session,message);
                }
                else
                {
                    ServerMessage.Mutable out = newMessage();
                    out.setChannel(message.getChannel());
                    out.setData(message.getData());
                    out.setId(message.getId());
                    channel.publish(session,out);
                }

                reply = createReply(message);
                reply.setSuccessful(true);
            }
            else
            {
                reply = createReply(message);
                error(reply,session==null?"402::unknown client":"403::Cannot publish");
            }
        }

        if (_logger.isDebugEnabled())
            _logger.debug("<< "+reply);
        return reply;
    }

    /* ------------------------------------------------------------ */
    protected void doPublish(ServerSessionImpl from, ServerChannelImpl to, final ServerMessage.Mutable mutable)
    {
        // check the parent channels
        String parent=to.getChannelId().getParent();
        while (parent!=null)
        {
            ServerChannelImpl c = _channels.get(parent.toString());
            if (c==null)
                return; // remove in progress
            if (c.isLazy())
                mutable.setLazy(true);
            parent=c.getChannelId().getParent();
        }

        // Get the array of listening channels
        final List<String> wildIds=to.getChannelId().getWilds();
        final ServerChannelImpl[] listening_channels = new ServerChannelImpl[wildIds.size()+1];
        listening_channels[wildIds.size()]=to;
        for (int i=wildIds.size();i-->0;)
            listening_channels[i]=_channels.get(wildIds.get(i));

        // Call the listeners
        for (int i=0;i<listening_channels.length;i++)
        {
            final ServerChannelImpl channel = listening_channels[i];
            if (channel==null)
                continue;

            if (channel.isLazy())
                mutable.setLazy(true);
            for (ServerChannelListener listener : channel.getListeners())
                if (listener instanceof MessageListener)
                    if (!((MessageListener)listener).onMessage(from,to,mutable))
                        return;
        }

        // Call the subscribers
        for (int i=0;i<listening_channels.length;i++)
        {
            final ServerChannelImpl channel = listening_channels[i];
            if (channel==null)
                continue;

            for (ServerSession session : channel.getSubscribers())
                ((ServerSessionImpl)session).doDeliver(from,mutable.asImmutable());
        }

        // Meta handlers
        if (to.isMeta())
        {
            for (ServerChannelListener listener : to.getListeners())
                if (listener instanceof BayeuxServerImpl.HandlerListener)
                    ((BayeuxServerImpl.HandlerListener)listener).onMessage(from,mutable);
        }
    }


    /* ------------------------------------------------------------ */
    public ServerMessage extendReply(ServerSessionImpl session, ServerMessage reply)
    {
        if (session!=null)
            reply = session.extendSend(reply);
        if (reply!=null && !extendSend(session,reply.asMutable()))
            reply=null;

        if (_logger.isDebugEnabled())
            _logger.debug("<  "+reply);

        return reply;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendRecv(ServerSessionImpl from, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension ext: _extensions)
                if (!ext.rcvMeta(from,message))
                    return false;
        }
        else
        {
            for (Extension ext: _extensions)
                if (!ext.rcv(from,message))
                    return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    protected boolean extendSend(ServerSessionImpl to, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            ListIterator<Extension> i = _extensions.listIterator(_extensions.size());
            while(i.hasPrevious())
                if (!i.previous().sendMeta(to,message))
                    return false;
        }
        else
        {
            ListIterator<Extension> i = _extensions.listIterator(_extensions.size());
            while(i.hasPrevious())
                if (!i.previous().send(message))
                    return false;
        }

        return true;
    }

    /* ------------------------------------------------------------ */
    boolean removeServerChannel(ServerChannelImpl channel)
    {
        if(_channels.remove(channel.getId(),channel))
        {
            _logger.debug("removed {}",channel);
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.ChannelListener)
                    ((ChannelListener)listener).channelRemoved(channel.getId());
            }
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    List<BayeuxServerListener> getListeners()
    {
        return _listeners;
    }

    /* ------------------------------------------------------------ */
    public List<String> getAllowedTransports()
    {
        return Collections.unmodifiableList(_allowedTransports);
    }

    /* ------------------------------------------------------------ */
    public Set<String> getKnownTransportNames()
    {
        return _transports.keySet();
    }

    /* ------------------------------------------------------------ */
    public Transport getTransport(String transport)
    {
        return _transports.get(transport);
    }

    /* ------------------------------------------------------------ */
    public void addTransport(Transport transport)
    {
        _transports.put(transport.getName(),transport);
    }

    /* ------------------------------------------------------------ */
    public void setAllowedTransports(String... allowed)
    {
        setAllowedTransports(Arrays.asList(allowed));
    }

    /* ------------------------------------------------------------ */
    public void setAllowedTransports(List<String> allowed)
    {
        _allowedTransports.clear();
        for (String transport : allowed)
        {
            if (_transports.containsKey(transport))
                _allowedTransports.add(transport);
        }
    }

    /* ------------------------------------------------------------ */
    protected void error(ServerMessage.Mutable reply, String error)
    {
        reply.put(Message.ERROR_FIELD,error);
        reply.setSuccessful(false);
    }

    /* ------------------------------------------------------------ */
    protected ServerMessage.Mutable createReply(ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply=newMessage();
        message.setAssociated(reply);
        reply.setAssociated(message);

        reply.setChannel(message.getChannel());
        String id=message.getId();
        if (id != null)
            reply.setId(id);
        return reply;
    }

    /* ------------------------------------------------------------ */
    public void doSweep()
    {
        final Map<String,Integer> dust = new HashMap<String, Integer>();

        for (ServerChannelImpl channel :_channels.values())
        {
           if (!dust.containsKey(channel.getId()))
               dust.put(channel.getId(),0);
           String parent=channel.getChannelId().getParent();
           if (parent!=null)
           {
               Integer children=dust.get(parent);
               dust.put(parent,children==null?1:(children+1));
           }
        }

        for (String channel : dust.keySet())
        {
            ServerChannelImpl sci=_channels.get(channel);
            if (sci!=null)
                sci.doSweep(dust.get(channel));
        }
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        StringBuilder b = new StringBuilder();

        ArrayList<ServerChannelImpl> children = new ArrayList<ServerChannelImpl>();
        for (ServerChannelImpl channel :_channels.values())
        {
            if (channel.getChannelId().depth()==1)
                children.add(channel);
        }

        int leaves=children.size();
        int i=0;
        for (ServerChannelImpl child : children)
        {
            b.append(" +-");
            child.dump(b,((++i==leaves)?"   ":" | "));
        }

        return b.toString();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    abstract class HandlerListener implements ServerChannel.ServerChannelListener
    {
        public abstract void onMessage(final ServerSessionImpl from, final ServerMessage.Mutable message);

    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class HandshakeHandler extends HandlerListener
    {
        @Override
        public void onMessage(ServerSessionImpl session, final Mutable message)
        {
            if (session==null)
                session = newServerSession();

            ServerMessage.Mutable reply=createReply(message);

            if (_policy != null && !_policy.canHandshake(BayeuxServerImpl.this,session,message))
            {
                error(reply,"403::Handshake denied");
                reply.getAdvice(true).put(Message.RECONNECT_FIELD,Message.RECONNECT_NONE_VALUE);
                return;
            }

            session.handshake();
            addServerSession(session);

            reply.setSuccessful(true);
            reply.put(Message.CLIENT_ID_FIELD,session.getId());
            reply.put(Message.VERSION_FIELD,"1.0");
            reply.put(Message.MIN_VERSION_FIELD,"1.0");
            reply.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD,getAllowedTransports());

        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class ConnectHandler extends HandlerListener
    {
        @Override
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);

            if (session == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }

            session.connect();

            // receive advice
            Map<String,Object> adviceIn=message.getAdvice();
            if (adviceIn != null)
            {
                Long timeout=(Long)adviceIn.get("timeout");
                session.setTimeout(timeout==null?-1:timeout);
                Long interval=(Long)adviceIn.get("interval");
                session.setInterval(interval==null?-1:interval);
            }
            else
            {
                session.setTimeout(-1L);
                session.setInterval(-1L);
            }

            // send advice
            Object adviceOut = session.takeAdvice();
            if (adviceOut!=null)
                reply.put(Message.ADVICE_FIELD,adviceOut);

            reply.setSuccessful(true);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class SubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (from == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }

            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);

            if (subscribe_id==null)
                error(reply,"403::cannot create");
            else
            {
                reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscribe_id);
                if (channel==null && getSecurityPolicy().canCreate(BayeuxServerImpl.this,from,subscribe_id,message))
                {
                    createIfAbsent(subscribe_id);
                    channel = (ServerChannelImpl)getChannel(subscribe_id);
                }

                if (channel==null)
                    error(reply,"403::cannot create");
                else if (!getSecurityPolicy().canSubscribe(BayeuxServerImpl.this,from,channel,message))
                    error(reply,"403::cannot subscribe");
                else
                {
                    if (from.isLocalSession() || !channel.isMeta() && !channel.isService())
                    {
                        if (channel.subscribe((ServerSessionImpl)from))
                            reply.setSuccessful(true);
                        else
                            error(reply,"403::subscribe failed");
                    }
                    else
                        reply.setSuccessful(true);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class UnsubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (from == null)
            {
                error(reply,"402::Unknown client");
                reply.put(Message.ADVICE_FIELD,_handshakeAdvice);
                return;
            }

            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);
            if (subscribe_id==null)
                error(reply,"400::no channel");
            else
            {
                reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);

                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscribe_id);
                if (channel==null)
                    error(reply,"400::no channel");
                else
                {
                    if (from.isLocalSession() || !channel.isMeta() && !channel.isService())
                        channel.unsubscribe((ServerSessionImpl)from);
                    reply.setSuccessful(true);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class DisconnectHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (session == null)
            {
                error(reply,"402::Unknown client");
                return;
            }

            removeServerSession(session,false);
            session.flush();

            reply.setSuccessful(true);
        }
    }
}
