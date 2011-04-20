package org.cometd.server;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxContext;
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
import org.cometd.server.transport.JSONPTransport;
import org.cometd.server.transport.JSONTransport;
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
    public static final String LOG_LEVEL = "logLevel";
    public static final int OFF_LOG_LEVEL = 0;
    public static final int CONFIG_LOG_LEVEL = 1;
    public static final int INFO_LOG_LEVEL = 2;
    public static final int DEBUG_LOG_LEVEL = 3;

    private final Logger _logger = Log.getLogger(getClass().getName() + "@" + System.identityHashCode(this));
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<BayeuxServerListener>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<String, ServerSessionImpl>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<String, ServerChannelImpl>();
    private final ConcurrentMap<String, ServerTransport> _transports = new ConcurrentHashMap<String, ServerTransport>();
    private final List<String> _allowedTransports = new CopyOnWriteArrayList<String>();
    private final ThreadLocal<AbstractServerTransport> _currentTransport = new ThreadLocal<AbstractServerTransport>();
    private final Map<String,Object> _options = new TreeMap<String, Object>();
    private final Timeout _timeout = new Timeout();

    private Timer _timer = new Timer();
    private Object _handshakeAdvice=new JSON.Literal("{\"reconnect\":\"handshake\",\"interval\":500}");
    private SecurityPolicy _policy=new DefaultSecurityPolicy();

    /* ------------------------------------------------------------ */
    public BayeuxServerImpl()
    {
        addTransport(new JSONTransport(this));
        addTransport(new JSONPTransport(this));
    }

    /* ------------------------------------------------------------ */
    public BayeuxServerImpl(List<ServerTransport> transports)
    {
        setTransports(transports);
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

        int logLevel = OFF_LOG_LEVEL;
        Object logLevelValue = getOption(LOG_LEVEL);
        if (logLevelValue != null)
        {
            logLevel = Integer.parseInt(String.valueOf(logLevelValue));
            getLogger().setDebugEnabled(logLevel > INFO_LOG_LEVEL);
        }

        if (logLevel >= CONFIG_LOG_LEVEL)
        {
            for (Map.Entry<String, Object> entry : getOptions().entrySet())
                getLogger().info(entry.getKey() + "=" + entry.getValue());
        }

        initializeMetaChannels();

        initializeDefaultTransports();

        List<String> allowedTransportNames = getAllowedTransports();
        if (allowedTransportNames.isEmpty())
            throw new IllegalStateException("No allowed transport names are configured, there must be at least one");

        for (String allowedTransportName : allowedTransportNames)
        {
            ServerTransport allowedTransport = getTransport(allowedTransportName);
            if (allowedTransport instanceof AbstractServerTransport)
                ((AbstractServerTransport)allowedTransport).init();
        }

        _timer = new Timer("BayeuxServer@" + hashCode(), true);
        long tick_interval = getOption("tickIntervalMs", 97);
        if (tick_interval > 0)
        {
            _timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    _timeout.tick(System.currentTimeMillis());
                }
            }, tick_interval, tick_interval);
        }

        long sweep_interval = getOption("sweepIntervalMs", 997);
        if (sweep_interval > 0)
        {
            _timer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    doSweep();

                    final long now = System.currentTimeMillis();
                    for (ServerSessionImpl session : _sessions.values())
                        session.sweep(now);
                }
            }, sweep_interval, sweep_interval);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        _listeners.clear();
        _extensions.clear();
        _sessions.clear();
        _channels.clear();
        _transports.clear();
        _allowedTransports.clear();
        _options.clear();
        _timer.cancel();
    }

    protected void initializeMetaChannels()
    {
        createIfAbsent(Channel.META_HANDSHAKE);
        createIfAbsent(Channel.META_CONNECT);
        createIfAbsent(Channel.META_SUBSCRIBE);
        createIfAbsent(Channel.META_UNSUBSCRIBE);
        createIfAbsent(Channel.META_DISCONNECT);
        getChannel(Channel.META_HANDSHAKE).addListener(new HandshakeHandler());
        getChannel(Channel.META_CONNECT).addListener(new ConnectHandler());
        getChannel(Channel.META_SUBSCRIBE).addListener(new SubscribeHandler());
        getChannel(Channel.META_UNSUBSCRIBE).addListener(new UnsubscribeHandler());
        getChannel(Channel.META_DISCONNECT).addListener(new DisconnectHandler());
    }

    /* ------------------------------------------------------------ */
    /** Initialize the default transports.
     * <p>This method creates  a {@link JSONTransport} and a {@link JSONPTransport}.
     * If no allowed transport have been set then adds all known transports as allowed transports.
     */
    protected void initializeDefaultTransports()
    {
        if (_allowedTransports.size()==0)
        {
            for (ServerTransport t : _transports.values())
                _allowedTransports.add(t.getName());
        }
        _logger.info("Allowed Transports:"+_allowedTransports);
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
    protected long getOption(String name, long dft)
    {
        Object val=getOption(name);
        if (val==null)
            return dft;
        if (val instanceof Number)
            return ((Number)val).longValue();
        return Long.parseLong(val.toString());
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
        _options.put(qualifiedName, value);
    }

    public void setOptions(Map<String, Object> options)
    {
        _options.putAll(options);
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
    public BayeuxContext getContext()
    {
        ServerTransport transport=_currentTransport.get();
        return transport==null?null:transport.getContext();
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
    public List<ServerSession> getSessions()
    {
        return Collections.unmodifiableList(new ArrayList<ServerSession>(_sessions.values()));
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
            boolean connected = ((ServerSessionImpl)session).removed(timedout);

            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.SessionListener)
                    ((SessionListener)listener).sessionRemoved(session,timedout);
            }

            return connected;
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
        return new ServerMessageImpl();
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable newMessage(ServerMessage tocopy)
    {
        ServerMessage.Mutable mutable = newMessage();
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

    /* ------------------------------------------------------------ */
    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    /* ------------------------------------------------------------ */
    public void addListener(BayeuxServerListener listener)
    {
        if (listener == null)
            throw new NullPointerException();
        _listeners.add(listener);
    }

    /* ------------------------------------------------------------ */
    public ServerChannel getChannel(String channelId)
    {
        return _channels.get(channelId);
    }

    /* ------------------------------------------------------------ */
    public List<ServerChannel> getChannels()
    {
        return Collections.unmodifiableList(new ArrayList<ServerChannel>(_channels.values()));
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
    public ServerMessage.Mutable handle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        if (_logger.isDebugEnabled())
            _logger.debug(">  " + message + " " + session);

        ServerMessage.Mutable reply = null;
        if (!extendRecv(session, message) || session != null && !session.extendRecv(message))
        {
            reply = createReply(message);
            error(reply, "404::message deleted");
        }
        else
        {
            if (_logger.isDebugEnabled())
                _logger.debug(">> " + message);

            String channelName = message.getChannel();

            ServerChannel channel;
            if (channelName == null)
            {
                reply = createReply(message);
                error(reply, "400::channel missing");
            }
            else
            {
                channel = getChannel(channelName);
                if (channel == null)
                {
                    Authorizer.Result creationResult = isCreationAuthorized(session, message, channelName);
                    if (creationResult instanceof Authorizer.Result.Denied)
                    {
                        reply = createReply(message);
                        String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                        error(reply, "403:" + denyReason + ":create denied");
                    }
                    else
                    {
                        createIfAbsent(channelName);
                        channel = getChannel(channelName);
                    }
                }

                if (channel != null)
                {
                    if (channel.isMeta())
                    {
                        if (session == null && !Channel.META_HANDSHAKE.equals(channelName))
                        {
                            reply = createReply(message);
                            unknownSession(reply);
                        }
                        else
                        {
                            doPublish(session, (ServerChannelImpl)channel, message);
                            reply = message.getAssociated();
                        }
                    }
                    else
                    {
                        if (session == null)
                        {
                            reply = createReply(message);
                            unknownSession(reply);
                        }
                        else
                        {
                            Authorizer.Result publishResult = isPublishAuthorized(channel, session, message);
                            if (publishResult instanceof Authorizer.Result.Denied)
                            {
                                reply = createReply(message);
                                String denyReason = ((Authorizer.Result.Denied)publishResult).getReason();
                                error(reply, "403:" + denyReason + ":publish denied");
                            }
                            else
                            {
                                channel.publish(session, message);
                                reply = createReply(message);
                                reply.setSuccessful(true);
                            }
                        }
                    }
                }
            }
        }

        // Here the reply may be null if this instance is stopped concurrently

        if (_logger.isDebugEnabled())
            _logger.debug("<< " + reply);
        return reply;
    }

    private Authorizer.Result isPublishAuthorized(ServerChannel channel, ServerSession session, ServerMessage message)
    {
        if (_policy != null && !_policy.canPublish(this, session, channel, message))
        {
            _logger.warn("{} denied Publish@{} by {}", session, channel.getId(), _policy);
            return Authorizer.Result.deny("denied_by_security_policy");
        }
        return isOperationAuthorized(Authorizer.Operation.PUBLISH, session, message, channel.getChannelId());
    }

    private Authorizer.Result isSubscribeAuthorized(ServerChannel channel, ServerSession session, ServerMessage message)
    {
        if (_policy != null && !_policy.canSubscribe(this, session, channel, message))
        {
            _logger.warn("{} denied Publish@{} by {}", session, channel, _policy);
            return Authorizer.Result.deny("denied_by_security_policy");
        }
        return isOperationAuthorized(Authorizer.Operation.SUBSCRIBE, session, message, channel.getChannelId());
    }

    private Authorizer.Result isCreationAuthorized(ServerSession session, ServerMessage message, String channel)
    {
        if (_policy != null && !_policy.canCreate(BayeuxServerImpl.this, session, channel, message))
        {
            _logger.warn("{} denied Create@{} by {}", session, message.getChannel(), _policy);
            return Authorizer.Result.deny("denied_by_security_policy");
        }
        return isOperationAuthorized(Authorizer.Operation.CREATE, session, message, new ChannelId(channel));
    }

    private Authorizer.Result isOperationAuthorized(Authorizer.Operation operation, ServerSession session, ServerMessage message, ChannelId channelId)
    {
        List<ServerChannel> channels = new ArrayList<ServerChannel>();
        for (String wildName : channelId.getWilds())
        {
            ServerChannelImpl channel = _channels.get(wildName);
            if (channel != null)
                channels.add(channel);
        }
        ServerChannelImpl candidate = _channels.get(channelId.toString());
        if (candidate != null)
            channels.add(candidate);

        boolean called = false;
        Authorizer.Result result = Authorizer.Result.ignore();
        for (ServerChannel channel : channels)
        {
            for (Authorizer authorizer : channel.getAuthorizers())
            {
                called = true;
                Authorizer.Result authorization = authorizer.authorize(operation, channelId, session, message);
                _logger.debug("Authorizer {} on channel {} {} {} for channel {}", authorizer, channel, authorization, operation, channelId);
                if (authorization instanceof Authorizer.Result.Denied)
                {
                    result = authorization;
                    break;
                }
                else if (authorization instanceof Authorizer.Result.Granted)
                {
                    result = authorization;
                }
            }
        }

        if (!called)
        {
            result = Authorizer.Result.grant();
            _logger.debug("No authorizers, {} for channel {} {}", operation, channelId, result);
        }
        else
        {
            if (result instanceof Authorizer.Result.Ignored)
            {
                result = Authorizer.Result.deny("denied_by_not_granting");
                _logger.debug("No authorizer granted {} for channel {}, authorization {}", operation, channelId, result);
            }
            else if (result instanceof Authorizer.Result.Granted)
            {
                _logger.debug("No authorizer denied {} for channel {}, authorization {}", operation, channelId, result);
            }
        }

        // We need to make sure that this method returns a boolean result (granted or denied)
        // but if it's denied, we need to return the object in order to access the deny reason
        assert !(result instanceof Authorizer.Result.Ignored);
        return result;
    }

    /* ------------------------------------------------------------ */
    protected void doPublish(ServerSessionImpl from, ServerChannelImpl to, final ServerMessage.Mutable mutable)
    {
        // check the parent channels
        String parent=to.getChannelId().getParent();
        while (parent!=null)
        {
            ServerChannelImpl c = _channels.get(parent);
            if (c==null)
                return; // remove in progress
            if (c.isLazy())
                mutable.setLazy(true);
            parent=c.getChannelId().getParent();
        }

        // Get the array of listening channels
        final List<String> wildIds=to.getChannelId().getWilds();
        final ServerChannelImpl[] wild_channels = new ServerChannelImpl[wildIds.size()];
        for (int i=wildIds.size();i-->0;)
            wild_channels[i]=_channels.get(wildIds.get(i));

        // Call the wild listeners
        for (final ServerChannelImpl channel : wild_channels)
        {
            if (channel == null)
                continue;

            if (channel.isLazy())
                mutable.setLazy(true);
            for (ServerChannelListener listener : channel.getListeners())
                if (listener instanceof MessageListener)
                    if (!((MessageListener)listener).onMessage(from, to, mutable))
                        return;
        }

        // Call the leaf listeners
        if (to.isLazy())
            mutable.setLazy(true);
        for (ServerChannelListener listener : to.getListeners())
            if (listener instanceof MessageListener)
                if (!((MessageListener)listener).onMessage(from, to, mutable))
                    return;

        // Exactly at this point, we convert the message to JSON and therefore
        // any further modification will be lost.
        // This is an optimization so that if the message is sent to a million
        // subscribers, we generate the JSON only once.
        // From now on, user code is passed a ServerMessage reference (and not
        // ServerMessage.Mutable), and we attempt to return immutable data
        // structures, even if it is not possible to guard against all cases.
        // For example, it is impossible to prevent things like
        // ((CustomObject)serverMessage.getData()).change() or
        // ((Map)serverMessage.getExt().get("map")).put().
        ((ServerMessageImpl)mutable).freeze();

        // Call the wild subscribers
        HashSet<String> wild_subscribers=null;
        for (final ServerChannelImpl channel : wild_channels)
        {
            if (channel == null)
                continue;

            for (ServerSession session : channel.getSubscribers())
            {
                if (wild_subscribers==null)
                    wild_subscribers=new HashSet<String>();

                if (wild_subscribers.add(session.getId()))
                    ((ServerSessionImpl)session).doDeliver(from, mutable);
            }
        }

        // Call the leaf subscribers
        for (ServerSession session : to.getSubscribers())
        {
            if (wild_subscribers==null || !wild_subscribers.contains(session.getId()))
                ((ServerSessionImpl)session).doDeliver(from, mutable);
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
    public ServerMessage.Mutable extendReply(ServerSessionImpl from, ServerSessionImpl to, ServerMessage.Mutable reply)
    {
        if (!extendSend(from, to, reply))
            return null;

        if (to != null)
        {
            if (reply.isMeta())
            {
                if(!to.extendSendMeta(reply))
                    return null;
            }
            else
            {
                ServerMessage newReply = to.extendSendMessage(reply);
                if (newReply == null)
                {
                    reply = null;
                }
                else if (newReply != reply)
                {
                    if (newReply instanceof ServerMessage.Mutable)
                        reply = (ServerMessage.Mutable)newReply;
                    else
                        reply = newMessage(newReply);
                }
            }
        }

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
    protected boolean extendSend(ServerSessionImpl from, ServerSessionImpl to, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            ListIterator<Extension> i = _extensions.listIterator(_extensions.size());
            while(i.hasPrevious())
            {
                if (!i.previous().sendMeta(to,message))
                {
                    if (_logger.isDebugEnabled())
                        _logger.debug("!  "+message);
                    return false;
                }
            }
        }
        else
        {
            ListIterator<Extension> i = _extensions.listIterator(_extensions.size());
            while(i.hasPrevious())
            {
                if (!i.previous().send(from,to,message))
                {
                    if (_logger.isDebugEnabled())
                        _logger.debug("!  "+message);
                    return false;
                }
            }
        }

        if (_logger.isDebugEnabled())
            _logger.debug("<  "+message);
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
    public Set<String> getKnownTransportNames()
    {
        return _transports.keySet();
    }

    /* ------------------------------------------------------------ */
    public ServerTransport getTransport(String transport)
    {
        return _transports.get(transport);
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated Use {@link #addTransport(ServerTransport)} instead
     */
    @Deprecated
    public void addTransport(Transport transport)
    {
        addTransport((ServerTransport)transport);
    }

    /* ------------------------------------------------------------ */
    public void addTransport(ServerTransport transport)
    {
        _transports.put(transport.getName(), transport);
    }

    /* ------------------------------------------------------------ */
    public void setTransports(ServerTransport... transports)
    {
        setTransports(Arrays.asList(transports));
    }

    /* ------------------------------------------------------------ */
    public void setTransports(List<ServerTransport> transports)
    {
        _transports.clear();
        for (ServerTransport transport : transports)
            addTransport(transport);
    }

    /* ------------------------------------------------------------ */
    public List<String> getAllowedTransports()
    {
        return Collections.unmodifiableList(_allowedTransports);
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
    protected void unknownSession(Mutable reply)
    {
        error(reply,"402::Unknown client");
        if (Channel.META_HANDSHAKE.equals(reply.getChannel()) || Channel.META_CONNECT.equals(reply.getChannel()))
            reply.put(Message.ADVICE_FIELD, _handshakeAdvice);
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
        for (ServerChannelImpl channel : _channels.values())
            channel.doSweep();

        for (ServerTransport transport : _transports.values())
        {
            if (transport instanceof AbstractServerTransport)
                ((AbstractServerTransport)transport).doSweep();
        }
    }

    /* ------------------------------------------------------------ */
    public String dump()
    {
        StringBuilder b = new StringBuilder();

        ArrayList<Object> children = new ArrayList<Object>();
        if (_policy!=null)
            children.add(_policy);

        for (ServerChannelImpl channel :_channels.values())
        {
            if (channel.getChannelId().depth()==1)
                children.add(channel);
        }

        int leaves=children.size();
        int i=0;
        for (Object child : children)
        {
            b.append(" +-");
            if (child instanceof ServerChannelImpl)
                ((ServerChannelImpl)child).dump(b,((++i==leaves)?"   ":" | "));
            else
                b.append(child.toString()).append("\n");
        }

        return b.toString();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    abstract class HandlerListener implements ServerChannel.ServerChannelListener
    {
        protected boolean isSessionUnknown(ServerSession session)
        {
            return session == null || getSession(session.getId()) == null;
        }

        public abstract void onMessage(final ServerSessionImpl from, final ServerMessage.Mutable message);
    }

    private class HandshakeHandler extends HandlerListener
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
                // The user's SecurityPolicy may have customized the response's advice
                Map<String, Object> advice = reply.getAdvice(true);
                if (!advice.containsKey(Message.RECONNECT_FIELD))
                    advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
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

    private class ConnectHandler extends HandlerListener
    {
        @Override
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);

            if (isSessionUnknown(session))
            {
                unknownSession(reply);
                return;
            }

            session.connect();

            // Handle incoming advice
            Map<String,Object> adviceIn=message.getAdvice();
            if (adviceIn != null)
            {
                Long timeout=(Long)adviceIn.get("timeout");
                session.updateTransientTimeout(timeout==null?-1:timeout);
                Long interval=(Long)adviceIn.get("interval");
                session.updateTransientInterval(interval==null?-1:interval);
                // Force the server to send the advice, as the client may
                // have forgotten it (for example because of a reload)
                session.reAdvise();
            }
            else
            {
                session.updateTransientTimeout(-1);
                session.updateTransientInterval(-1);
            }

            // Send advice
            Object adviceOut = session.takeAdvice();
            if (adviceOut!=null)
                reply.put(Message.ADVICE_FIELD,adviceOut);

            reply.setSuccessful(true);
        }
    }

    private class SubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply = createReply(message);
            if (isSessionUnknown(from))
            {
                unknownSession(reply);
                return;
            }

            String subscription = (String)message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD, subscription);

            if (subscription == null)
            {
                error(reply, "403::subscription missing");
            }
            else
            {
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscription);
                if (channel == null)
                {
                    Authorizer.Result creationResult = isCreationAuthorized(from, message, subscription);
                    if (creationResult instanceof Authorizer.Result.Denied)
                    {
                        String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                        error(reply, "403:" + denyReason + ":create denied");
                    }
                    else
                    {
                        createIfAbsent(subscription);
                        channel = (ServerChannelImpl)getChannel(subscription);
                    }
                }

                if (channel != null)
                {
                    Authorizer.Result subscribeResult = isSubscribeAuthorized(channel, from, message);
                    if (subscribeResult instanceof Authorizer.Result.Denied)
                    {
                        String denyReason = ((Authorizer.Result.Denied)subscribeResult).getReason();
                        error(reply, "403:" + denyReason + ":subscribe denied");
                    }
                    else
                    {
                        // Reduces the window of time where a server-side expiration
                        // or a concurrent disconnect causes the invalid client to be
                        // registered as subscriber and hence being kept alive by the
                        // fact that the channel references it.
                        if (!isSessionUnknown(from))
                        {
                            if (from.isLocalSession() || !channel.isMeta() && !channel.isService())
                            {
                                if (channel.subscribe(from))
                                    reply.setSuccessful(true);
                                else
                                    error(reply, "403::subscribe failed");
                            }
                            else
                            {
                                reply.setSuccessful(true);
                            }
                        }
                        else
                        {
                            unknownSession(reply);
                        }
                    }
                }
            }
        }
    }

    private class UnsubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (isSessionUnknown(from))
            {
                unknownSession(reply);
                return;
            }

            String subscribe_id=(String)message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);
            if (subscribe_id==null)
                error(reply,"400::channel missing");
            else
            {
                reply.put(Message.SUBSCRIPTION_FIELD,subscribe_id);

                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscribe_id);
                if (channel==null)
                    error(reply,"400::channel missing");
                else
                {
                    if (from.isLocalSession() || !channel.isMeta() && !channel.isService())
                        channel.unsubscribe(from);
                    reply.setSuccessful(true);
                }
            }
        }
    }

    private class DisconnectHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply=createReply(message);
            if (isSessionUnknown(session))
            {
                unknownSession(reply);
                return;
            }

            removeServerSession(session,false);
            session.flush();

            reply.setSuccessful(true);
        }
    }
}
