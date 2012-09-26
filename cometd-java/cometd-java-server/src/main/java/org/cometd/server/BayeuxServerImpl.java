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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
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
import org.cometd.common.JSONContext;
import org.cometd.server.transport.JSONPTransport;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
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
    public static final String JSON_CONTEXT = "jsonContext";

    private final Logger _logger = LoggerFactory.getLogger(getClass().getName() + "." + System.identityHashCode(this));
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerTransport> _transports = new ConcurrentHashMap<>();
    private final List<String> _allowedTransports = new CopyOnWriteArrayList<>();
    private final ThreadLocal<AbstractServerTransport> _currentTransport = new ThreadLocal<>();
    private final Map<String,Object> _options = new TreeMap<>();
    private final Timeout _timeout = new Timeout();
    private final Map<String, Object> _handshakeAdvice;
    private SecurityPolicy _policy = new DefaultSecurityPolicy();
    private int _logLevel = OFF_LOG_LEVEL;
    private JSONContext.Server _jsonContext;
    private Timer _timer;

    public BayeuxServerImpl()
    {
        addTransport(new JSONTransport(this));
        addTransport(new JSONPTransport(this));
        _handshakeAdvice = new HashMap<>(2);
        _handshakeAdvice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
        _handshakeAdvice.put(Message.INTERVAL_FIELD, 0L);
    }

    /**
     * @return The Logger used by this BayeuxServer instance
     */
    public Logger getLogger()
    {
        return _logger;
    }

    private void debug(String message, Object... args)
    {
        if (_logLevel >= DEBUG_LOG_LEVEL)
            _logger.info(message, args);
        else
            _logger.debug(message, args);
    }

    int getLogLevel()
    {
        return _logLevel;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _logLevel = OFF_LOG_LEVEL;
        Object logLevelValue = getOption(LOG_LEVEL);
        if (logLevelValue != null)
            _logLevel = Integer.parseInt(String.valueOf(logLevelValue));

        if (_logLevel >= CONFIG_LOG_LEVEL)
        {
            for (Map.Entry<String, Object> entry : getOptions().entrySet())
                _logger.info("{}={}", entry.getKey(), entry.getValue());
        }

        initializeMetaChannels();

        initializeJSONContext();

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
                    sweep();
                }
            }, sweep_interval, sweep_interval);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        for (ServerTransport transport : _transports.values())
            if (transport instanceof AbstractServerTransport)
                ((AbstractServerTransport)transport).destroy();

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
        createChannelIfAbsent(Channel.META_HANDSHAKE).getReference().addListener(new HandshakeHandler());
        createChannelIfAbsent(Channel.META_CONNECT).getReference().addListener(new ConnectHandler());
        createChannelIfAbsent(Channel.META_SUBSCRIBE).getReference().addListener(new SubscribeHandler());
        createChannelIfAbsent(Channel.META_UNSUBSCRIBE).getReference().addListener(new UnsubscribeHandler());
        createChannelIfAbsent(Channel.META_DISCONNECT).getReference().addListener(new DisconnectHandler());
    }

    protected void initializeJSONContext() throws Exception
    {
        Object option = getOption(JSON_CONTEXT);
        if (option == null)
        {
            _jsonContext = new JettyJSONContextServer();
        }
        else
        {
            if (option instanceof String)
            {
                Class<?> jsonContextClass = Thread.currentThread().getContextClassLoader().loadClass((String)option);
                if (JSONContext.Server.class.isAssignableFrom(jsonContextClass))
                {
                    _jsonContext = (JSONContext.Server)jsonContextClass.newInstance();
                }
                else
                {
                    throw new IllegalArgumentException("Invalid " + JSONContext.Server.class.getName() + " implementation class");
                }
            }
            else if (option instanceof JSONContext.Server)
            {
                _jsonContext = (JSONContext.Server)option;
            }
            else
            {
                throw new IllegalArgumentException("Invalid " + JSONContext.Server.class.getName() + " implementation class");
            }
        }
        _options.put(JSON_CONTEXT, _jsonContext);
    }

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
        debug("Allowed Transports: {}", _allowedTransports);
    }

    public void startTimeout(Timeout.Task task, long interval)
    {
        _timeout.schedule(task, interval);
    }

    public void cancelTimeout(Timeout.Task task)
    {
        task.cancel();
    }

    public ChannelId newChannelId(String id)
    {
        ServerChannelImpl channel = _channels.get(id);
        if (channel!=null)
            return channel.getChannelId();
        return new ChannelId(id);
    }

    public Map<String,Object> getOptions()
    {
        return _options;
    }

    /**
     * @see org.cometd.bayeux.Bayeux#getOption(java.lang.String)
     */
    public Object getOption(String qualifiedName)
    {
        return _options.get(qualifiedName);
    }

    /** Get an option value as a long
     * @param name The option name
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

    /**
     * @see org.cometd.bayeux.Bayeux#getOptionNames()
     */
    public Set<String> getOptionNames()
    {
        return _options.keySet();
    }

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

    public long randomLong()
    {
        return _random.nextLong();
    }

    public void setCurrentTransport(AbstractServerTransport transport)
    {
        _currentTransport.set(transport);
    }

    public ServerTransport getCurrentTransport()
    {
        return _currentTransport.get();
    }

    public BayeuxContext getContext()
    {
        ServerTransport transport=_currentTransport.get();
        return transport==null?null:transport.getContext();
    }

    public SecurityPolicy getSecurityPolicy()
    {
        return _policy;
    }

    public boolean createIfAbsent(String channelName, Initializer... initializers)
    {
        return createChannelIfAbsent(channelName, initializers).isMarked();
    }

    private AtomicMarkableReference<ServerChannelImpl> createChannelIfAbsent(String channelName, Initializer... initializers)
    {
        boolean initialized = false;
        ServerChannelImpl channel = _channels.get(channelName);
        if (channel == null)
        {
            ChannelId channelId = new ChannelId(channelName);

            // Be sure the parent is there
            ServerChannelImpl parentChannel = null;
            if (channelId.depth() > 1)
            {
                String parentName = channelId.getParent();
                // If the parent needs to be re-created, we are missing its initializers,
                // but there is nothing we can do: in this case, the application needs
                // to make the parent persistent through an initializer.
                parentChannel = createChannelIfAbsent(parentName).getReference();
            }

            ServerChannelImpl candidate = new ServerChannelImpl(this, channelId, parentChannel);
            channel = _channels.putIfAbsent(channelName, candidate);
            if (channel == null)
            {
                // My candidate channel was added to the map, so I'd better initialize it

                channel = candidate;
                debug("Added channel {}", channel);

                try
                {
                    for (Initializer initializer : initializers)
                        notifyConfigureChannel(initializer, channel);

                    for (BayeuxServer.BayeuxServerListener listener : _listeners)
                    {
                        if (listener instanceof ServerChannel.Initializer)
                            notifyConfigureChannel((Initializer)listener, channel);
                    }
                }
                finally
                {
                    channel.initialized();
                }

                for (BayeuxServer.BayeuxServerListener listener : _listeners)
                {
                    if (listener instanceof BayeuxServer.ChannelListener)
                        notifyChannelAdded((ChannelListener)listener, channel);
                }

                initialized = true;
            }
        }
        else
        {
            channel.resetSweeperPasses();
            // Double check if the sweeper removed this channel between the check at the top and here.
            // This is not 100% fool proof (e.g. this thread is preempted long enough for the sweeper
            // to remove the channel, but the alternative is to have a global lock)
            _channels.putIfAbsent(channelName, channel);

        }
        // Another thread may add this channel concurrently, so wait until it is initialized
        channel.waitForInitialized();
        return new AtomicMarkableReference<>(channel, initialized);
    }

    private void notifyConfigureChannel(Initializer listener, ServerChannel channel)
    {
        try
        {
            listener.configureChannel(channel);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifyChannelAdded(ChannelListener listener, ServerChannel channel)
    {
        try
        {
            listener.channelAdded(channel);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    public List<ServerSession> getSessions()
    {
        return Collections.unmodifiableList(new ArrayList<ServerSession>(_sessions.values()));
    }

    public ServerSession getSession(String clientId)
    {
        if (clientId==null)
            return null;
        return _sessions.get(clientId);
    }

    protected void addServerSession(ServerSessionImpl session)
    {
        _sessions.put(session.getId(),session);
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.SessionListener)
                notifySessionAdded((SessionListener)listener, session);
        }
    }

    private void notifySessionAdded(SessionListener listener, ServerSession session)
    {
        try
        {
            listener.sessionAdded(session);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    /**
     * @param session the session to remove
     * @param timedout whether the remove reason is server-side expiration
     * @return true if the session was removed and was connected
     */
    public boolean removeServerSession(ServerSession session,boolean timedout)
    {
        debug("Removing session {}, timed out: {}", session, timedout);

        ServerSessionImpl removed =_sessions.remove(session.getId());

        if (removed == session)
        {
            // Invoke BayeuxServer.SessionListener first, so that the application
            // can be "pre-notified" that a session is being removed before the
            // application gets notifications of channel unsubscriptions
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.SessionListener)
                    notifySessionRemoved((SessionListener)listener, session, timedout);
            }

            return ((ServerSessionImpl)session).removed(timedout);
        }
        else
            return false;
    }

    private void notifySessionRemoved(SessionListener listener, ServerSession session, boolean timedout)
    {
        try
        {
            listener.sessionRemoved(session, timedout);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected ServerSessionImpl newServerSession()
    {
        return new ServerSessionImpl(this);
    }

    public LocalSession newLocalSession(String idHint)
    {
        return new LocalSessionImpl(this,idHint);
    }

    public ServerMessage.Mutable newMessage()
    {
        return new ServerMessageImpl();
    }

    public ServerMessage.Mutable newMessage(ServerMessage tocopy)
    {
        ServerMessage.Mutable mutable = newMessage();
        for (String key : tocopy.keySet())
            mutable.put(key,tocopy.get(key));
        return mutable;
    }

    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _policy=securityPolicy;
    }

    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
    }

    public List<Extension> getExtensions()
    {
        return Collections.unmodifiableList(_extensions);
    }

    public void addListener(BayeuxServerListener listener)
    {
        if (listener == null)
            throw new NullPointerException();
        _listeners.add(listener);
    }

    public ServerChannel getChannel(String channelId)
    {
        ServerChannelImpl channel = _channels.get(channelId);
        if (channel != null)
            channel.waitForInitialized();
        return channel;
    }

    public List<ServerChannel> getChannels()
    {
        List<ServerChannel> result = new ArrayList<>();
        for (ServerChannelImpl channel : _channels.values())
        {
            channel.waitForInitialized();
            result.add(channel);
        }
        return result;
    }

    public void removeListener(BayeuxServerListener listener)
    {
        _listeners.remove(listener);
    }

    /** Extend and handle in incoming message.
     * @param session The session if known
     * @param message The message.
     * @return An unextended reply message
     */
    public ServerMessage.Mutable handle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        debug(">  {} {}", message, session);

        Mutable reply = createReply(message);
        if (!extendRecv(session, message) || session != null && !session.extendRecv(message))
        {
            error(reply, "404::message deleted");
        }
        else
        {
            debug(">> {}", message);

            String channelName = message.getChannel();

            ServerChannel channel;
            if (channelName == null)
            {
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
                        String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                        error(reply, "403:" + denyReason + ":create denied");
                    }
                    else
                    {
                        channel = createChannelIfAbsent(channelName).getReference();
                    }
                }

                if (channel != null)
                {
                    if (channel.isMeta())
                    {
                        if (session == null && !Channel.META_HANDSHAKE.equals(channelName))
                        {
                            unknownSession(reply);
                        }
                        else
                        {
                            doPublish(session, (ServerChannelImpl)channel, message);
                        }
                    }
                    else
                    {
                        if (session == null)
                        {
                            unknownSession(reply);
                        }
                        else
                        {
                            Authorizer.Result publishResult = isPublishAuthorized(channel, session, message);
                            if (publishResult instanceof Authorizer.Result.Denied)
                            {
                                String denyReason = ((Authorizer.Result.Denied)publishResult).getReason();
                                error(reply, "403:" + denyReason + ":publish denied");
                            }
                            else
                            {
                                channel.publish(session, message);
                                reply.setSuccessful(true);
                            }
                        }
                    }
                }
            }
        }

        // Here the reply may be null if this instance is stopped concurrently

        debug("<< {}", reply);
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
            _logger.warn("{} denied Subscribe@{} by {}", session, channel, _policy);
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
        List<ServerChannel> channels = new ArrayList<>();
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
                debug("Authorizer {} on channel {} {} {} for channel {}", authorizer, channel, authorization, operation, channelId);
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
            debug("No authorizers, {} for channel {} {}", operation, channelId, result);
        }
        else
        {
            if (result instanceof Authorizer.Result.Ignored)
            {
                result = Authorizer.Result.deny("denied_by_not_granting");
                debug("No authorizer granted {} for channel {}, authorization {}", operation, channelId, result);
            }
            else if (result instanceof Authorizer.Result.Granted)
            {
                debug("No authorizer denied {} for channel {}, authorization {}", operation, channelId, result);
            }
        }

        // We need to make sure that this method returns a boolean result (granted or denied)
        // but if it's denied, we need to return the object in order to access the deny reason
        assert !(result instanceof Authorizer.Result.Ignored);
        return result;
    }

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
                    if (!notifyOnMessage((MessageListener)listener, from, to, mutable))
                        return;
        }

        // Call the leaf listeners
        if (to.isLazy())
            mutable.setLazy(true);
        for (ServerChannelListener listener : to.getListeners())
            if (listener instanceof MessageListener)
                if (!notifyOnMessage((MessageListener)listener, from, to, mutable))
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
        freeze(mutable);

        // Call the wild subscribers, which can only get broadcast messages.
        // We need a special treatment in case of subscription to /**, otherwise
        // we will deliver meta messages and service messages as if it could be
        // possible to subscribe to meta channels and service channels.
        Set<String> wildSubscribers = null;
        if (ChannelId.isBroadcast(mutable.getChannel()))
        {
            for (final ServerChannelImpl channel : wild_channels)
            {
                if (channel == null)
                    continue;

                for (ServerSession session : channel.getSubscribers())
                {
                    if (wildSubscribers == null)
                        wildSubscribers = new HashSet<>();
                    if (wildSubscribers.add(session.getId()))
                        ((ServerSessionImpl)session).doDeliver(from, mutable);
                }
            }
        }

        // Call the leaf subscribers
        for (ServerSession session : to.getSubscribers())
        {
            if (wildSubscribers == null || !wildSubscribers.contains(session.getId()))
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

    public void freeze(Mutable mutable)
    {
        ServerMessageImpl message = (ServerMessageImpl)mutable;
        if (message.isFrozen())
            return;
        String json = _jsonContext.generate(message);
        message.freeze(json);
    }

    private boolean notifyOnMessage(MessageListener listener, ServerSession from, ServerChannel to, Mutable mutable)
    {
        try
        {
            return listener.onMessage(from, to, mutable);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
            return true;
        }
    }

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

    protected boolean extendRecv(ServerSession from, ServerMessage.Mutable message)
    {
        if (message.isMeta())
        {
            for (Extension extension : _extensions)
                if (!notifyRcvMeta(extension, from, message))
                    return false;
        }
        else
        {
            for (Extension extension : _extensions)
                if (!notifyRcv(extension, from, message))
                    return false;
        }
        return true;
    }

    private boolean notifyRcvMeta(Extension extension, ServerSession from, Mutable message)
    {
        try
        {
            return extension.rcvMeta(from, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifyRcv(Extension extension, ServerSession from, Mutable message)
    {
        try
        {
            return extension.rcv(from, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    protected boolean extendSend(ServerSession from, ServerSession to, Mutable message)
    {
        if (message.isMeta())
        {
            // Cannot use listIterator(int): it is not thread safe
            ListIterator<Extension> i = _extensions.listIterator();
            while (i.hasNext())
                i.next();
            while (i.hasPrevious())
            {
                final Extension extension = i.previous();
                if (!notifySendMeta(extension, to, message))
                {
                    debug("Extension {} interrupted message processing for {}", extension, message);
                    return false;
                }
            }
        }
        else
        {
            ListIterator<Extension> i = _extensions.listIterator();
            while (i.hasNext())
                i.next();
            while (i.hasPrevious())
            {
                final Extension extension = i.previous();
                if (!notifySend(extension, from, to, message))
                {
                    debug("Extension {} interrupted message processing for {}", extension, message);
                    return false;
                }
            }
        }

        debug("<  {}", message);
        return true;
    }

    private boolean notifySendMeta(Extension extension, ServerSession to, Mutable message)
    {
        try
        {
            return extension.sendMeta(to, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    private boolean notifySend(Extension extension, ServerSession from, ServerSession to, Mutable message)
    {
        try
        {
            return extension.send(from, to, message);
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    protected boolean removeServerChannel(ServerChannelImpl channel)
    {
        if(_channels.remove(channel.getId(),channel))
        {
            debug("Removed channel {}",channel);
            for (BayeuxServerListener listener : _listeners)
            {
                if (listener instanceof BayeuxServer.ChannelListener)
                    notifyChannelRemoved((ChannelListener)listener, channel);
            }
            return true;
        }
        return false;
    }

    private void notifyChannelRemoved(ChannelListener listener, ServerChannelImpl channel)
    {
        try
        {
            listener.channelRemoved(channel.getId());
        }
        catch (Exception x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected List<BayeuxServerListener> getListeners()
    {
        return Collections.unmodifiableList(_listeners);
    }

    public Set<String> getKnownTransportNames()
    {
        return _transports.keySet();
    }

    public ServerTransport getTransport(String transport)
    {
        return _transports.get(transport);
    }

    public void addTransport(ServerTransport transport)
    {
        debug("addTransport {} from {}",transport.getName(),transport.getClass());
        _transports.put(transport.getName(), transport);
    }

    public void setTransports(ServerTransport... transports)
    {
        setTransports(Arrays.asList(transports));
    }

    public void setTransports(List<ServerTransport> transports)
    {
        _transports.clear();
        for (ServerTransport transport : transports)
            addTransport(transport);
    }

    public List<ServerTransport> getTransports()
    {
        return new ArrayList<>(_transports.values());
    }

    public List<String> getAllowedTransports()
    {
        return Collections.unmodifiableList(_allowedTransports);
    }

    public void setAllowedTransports(String... allowed)
    {
        setAllowedTransports(Arrays.asList(allowed));
    }

    public void setAllowedTransports(List<String> allowed)
    {
        debug("setAllowedTransport {} of {}",allowed,_transports);
        _allowedTransports.clear();
        for (String transport : allowed)
        {
            if (_transports.containsKey(transport))
                _allowedTransports.add(transport);
        }
        debug("allowedTransports ", _allowedTransports);
    }

    protected void unknownSession(Mutable reply)
    {
        error(reply, "402::Unknown client");
        if (Channel.META_HANDSHAKE.equals(reply.getChannel()) || Channel.META_CONNECT.equals(reply.getChannel()))
            reply.put(Message.ADVICE_FIELD, _handshakeAdvice);
    }

    protected void error(ServerMessage.Mutable reply, String error)
    {
        reply.put(Message.ERROR_FIELD, error);
        reply.setSuccessful(false);
    }

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

    public void sweep()
    {
        for (ServerChannelImpl channel : _channels.values())
            channel.sweep();

        for (ServerTransport transport : _transports.values())
        {
            if (transport instanceof AbstractServerTransport)
                ((AbstractServerTransport)transport).sweep();
        }

        long now = System.currentTimeMillis();
        for (ServerSessionImpl session : _sessions.values())
            session.sweep(now);
    }

    public String dump()
    {
        StringBuilder b = new StringBuilder();

        ArrayList<Object> children = new ArrayList<>();
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

    abstract class HandlerListener implements ServerChannel.ServerChannelListener
    {
        protected boolean isSessionUnknown(ServerSession session)
        {
            return session == null || getSession(session.getId()) == null;
        }

        protected List<String> toChannelList(Object channels)
        {
            if (channels instanceof String)
                return Collections.singletonList((String)channels);

            if (channels instanceof Object[])
            {
                Object[] array=(Object[])channels;
                List<String> channelList=new ArrayList<>();
                for (Object o:array)
                    channelList.add(String.valueOf(o));
                return channelList;
            }

            if (channels instanceof List)
            {
                List<?> list=(List<?>)channels;
                List<String> channelList=new ArrayList<>();
                for (Object o:list)
                    channelList.add(String.valueOf(o));
                return channelList;
            }

            return null;
        }
        public abstract void onMessage(final ServerSessionImpl from, final ServerMessage.Mutable message);
    }

    private class HandshakeHandler extends HandlerListener
    {
        @Override
        public void onMessage(ServerSessionImpl session, final Mutable message)
        {
            if (session == null)
                session = newServerSession();

            ServerMessage.Mutable reply = message.getAssociated();
            if (_policy != null && !_policy.canHandshake(BayeuxServerImpl.this, session, message))
            {
                error(reply, "403::Handshake denied");
                // The user's SecurityPolicy may have customized the response's advice
                Map<String, Object> advice = reply.getAdvice(true);
                if (!advice.containsKey(Message.RECONNECT_FIELD))
                    advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                return;
            }

            session.handshake();
            addServerSession(session);

            reply.setSuccessful(true);
            reply.put(Message.CLIENT_ID_FIELD, session.getId());
            reply.put(Message.VERSION_FIELD, "1.0");
            reply.put(Message.MIN_VERSION_FIELD, "1.0");
            reply.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, getAllowedTransports());
        }
    }

    private class ConnectHandler extends HandlerListener
    {
        @Override
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply = message.getAssociated();

            if (isSessionUnknown(session))
            {
                unknownSession(reply);
                return;
            }

            session.connect();

            // Handle incoming advice
            Map<String, Object> adviceIn = message.getAdvice();
            if (adviceIn != null)
            {
                Number timeout = (Number)adviceIn.get("timeout");
                session.updateTransientTimeout(timeout == null ? -1L : timeout.longValue());
                Number interval = (Number)adviceIn.get("interval");
                session.updateTransientInterval(interval == null ? -1L : interval.longValue());
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
            Map<String, Object> adviceOut = session.takeAdvice();
            if (adviceOut != null)
                reply.put(Message.ADVICE_FIELD, adviceOut);

            reply.setSuccessful(true);
        }
    }

    private class SubscribeHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl from, final Mutable message)
        {
            ServerMessage.Mutable reply = message.getAssociated();
            if (isSessionUnknown(from))
            {
                unknownSession(reply);
                return;
            }

            Object subscriptionField = message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD, subscriptionField);

            if (subscriptionField == null)
            {
                error(reply, "403::subscription_missing");
                return;
            }

            List<String> subscriptions = toChannelList(subscriptionField);
            if (subscriptions==null)
            {
                error(reply, "403::subscription_invalid");
                return;
            }

            for (String subscription : subscriptions)
            {
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscription);
                if (channel == null)
                {
                    Authorizer.Result creationResult = isCreationAuthorized(from, message, subscription);
                    if (creationResult instanceof Authorizer.Result.Denied)
                    {
                        String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                        error(reply, "403:" + denyReason + ":create_denied");
                        break;
                    }
                    else
                    {
                        channel = createChannelIfAbsent(subscription).getReference();
                    }
                }

                if (channel != null)
                {
                    Authorizer.Result subscribeResult = isSubscribeAuthorized(channel, from, message);
                    if (subscribeResult instanceof Authorizer.Result.Denied)
                    {
                        String denyReason = ((Authorizer.Result.Denied)subscribeResult).getReason();
                        error(reply, "403:" + denyReason + ":subscribe_denied");
                        break;
                    }
                    else
                    {
                        // Reduces the window of time where a server-side expiration
                        // or a concurrent disconnect causes the invalid client to be
                        // registered as subscriber and hence being kept alive by the
                        // fact that the channel references it.
                        if (!isSessionUnknown(from))
                        {
                            if (channel.subscribe(from))
                            {
                                reply.setSuccessful(true);
                            }
                            else
                            {
                                error(reply, "403::subscribe_failed");
                                break;
                            }
                        }
                        else
                        {
                            unknownSession(reply);
                            break;
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
            ServerMessage.Mutable reply = message.getAssociated();
            if (isSessionUnknown(from))
            {
                unknownSession(reply);
                return;
            }

            Object subscriptionField = message.get(Message.SUBSCRIPTION_FIELD);
            reply.put(Message.SUBSCRIPTION_FIELD, subscriptionField);

            if (subscriptionField == null)
            {
                error(reply, "403::subscription_missing");
                return;
            }

            List<String> subscriptions = toChannelList(subscriptionField);
            if (subscriptions==null)
            {
                error(reply, "403::subscription_invalid");
                return;
            }

            for (String subscription : subscriptions)
            {
                ServerChannelImpl channel = (ServerChannelImpl)getChannel(subscription);
                if (channel == null)
                {
                    error(reply, "400::channel_missing");
                    break;
                }
                else
                {
                    if (channel.unsubscribe(from))
                    {
                        reply.setSuccessful(true);
                    }
                    else
                    {
                        error(reply, "403::unsubscribe_failed");
                        break;
                    }
                }
            }
        }
    }

    private class DisconnectHandler extends HandlerListener
    {
        public void onMessage(final ServerSessionImpl session, final Mutable message)
        {
            ServerMessage.Mutable reply = message.getAssociated();
            if (isSessionUnknown(session))
            {
                unknownSession(reply);
                return;
            }

            removeServerSession(session, false);
            session.flush();

            reply.setSuccessful(true);
        }
    }
}
