/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel.Initializer;
import org.cometd.bayeux.server.ConfigurableServerChannel.ServerChannelListener;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerChannel.MessageListener;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.JSONContext;
import org.cometd.server.transport.AbstractHttpTransport;
import org.cometd.server.transport.AsyncJSONTransport;
import org.cometd.server.transport.JSONPTransport;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("The CometD server")
public class BayeuxServerImpl extends AbstractLifeCycle implements BayeuxServer
{
    public static final String ALLOWED_TRANSPORTS_OPTION = "allowedTransports";
    public static final String SWEEP_PERIOD_OPTION = "sweepPeriod";
    public static final String TRANSPORTS_OPTION = "transports";

    private final Logger _logger = LoggerFactory.getLogger(getClass().getName() + "." + Integer.toHexString(System.identityHashCode(this)));
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<>();
    private final Map<String, ServerTransport> _transports = new LinkedHashMap<>(); // Order is important
    private final List<String> _allowedTransports = new ArrayList<>();
    private final ThreadLocal<AbstractServerTransport> _currentTransport = new ThreadLocal<>();
    private final Map<String, Object> _options = new TreeMap<>();
    private final Scheduler _scheduler = new ScheduledExecutorScheduler("BayeuxServer" + hashCode() + " Scheduler", false);
    private SecurityPolicy _policy = new DefaultSecurityPolicy();
    private JSONContext.Server _jsonContext;

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        initializeMetaChannels();
        initializeJSONContext();
        initializeServerTransports();

        _scheduler.start();

        long defaultSweepPeriod = 997;
        long sweepPeriodOption = getOption(SWEEP_PERIOD_OPTION, defaultSweepPeriod);
        if (sweepPeriodOption < 0)
            sweepPeriodOption = defaultSweepPeriod;
        final long sweepPeriod = sweepPeriodOption;
        _scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                sweep();
                _scheduler.schedule(this, sweepPeriod, TimeUnit.MILLISECONDS);
            }
        }, sweepPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        for (String allowedTransportName : getAllowedTransports())
        {
            ServerTransport transport = getTransport(allowedTransportName);
            if (transport instanceof AbstractServerTransport)
                ((AbstractServerTransport)transport).destroy();
        }

        _listeners.clear();
        _extensions.clear();
        _sessions.clear();
        _channels.clear();
        _transports.clear();
        _allowedTransports.clear();
        _options.clear();
        _scheduler.stop();
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
        Object option = getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
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
                    _jsonContext = (JSONContext.Server)jsonContextClass.newInstance();
                else
                    throw new IllegalArgumentException("Invalid " + JSONContext.Server.class.getName() + " implementation class");
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
        _options.put(AbstractServerTransport.JSON_CONTEXT_OPTION, _jsonContext);
    }

    protected void initializeServerTransports()
    {
        if (_transports.isEmpty())
        {
            String option = (String)getOption(TRANSPORTS_OPTION);
            if (option == null)
            {
                // Order is important, see #findHttpTransport()
                ServerTransport transport = newWebSocketTransport();
                if (transport != null)
                    addTransport(transport);
                addTransport(newJSONTransport());
                addTransport(new JSONPTransport(this));
            }
            else
            {
                for (String className : option.split(","))
                {
                    ServerTransport transport = newServerTransport(className.trim());
                    if (transport != null)
                        addTransport(transport);
                }

                if (_transports.isEmpty())
                    throw new IllegalArgumentException("Option '" + TRANSPORTS_OPTION +
                            "' does not contain a valid list of server transport class names");
            }
        }

        if (_allowedTransports.isEmpty())
        {
            String option = (String)getOption(ALLOWED_TRANSPORTS_OPTION);
            if (option == null)
            {
                _allowedTransports.addAll(_transports.keySet());
            }
            else
            {
                for (String transportName : option.split(","))
                {
                    if (_transports.containsKey(transportName))
                        _allowedTransports.add(transportName);
                }

                if (_allowedTransports.isEmpty())
                    throw new IllegalArgumentException("Option '" + ALLOWED_TRANSPORTS_OPTION +
                            "' does not contain at least one configured server transport name");
            }
        }

        List<String> activeTransports = new ArrayList<>();
        for (String transportName : _allowedTransports)
        {
            ServerTransport serverTransport = getTransport(transportName);
            if (serverTransport instanceof AbstractServerTransport)
            {
                ((AbstractServerTransport)serverTransport).init();
                activeTransports.add(serverTransport.getName());
            }
        }
        _logger.debug("Active transports: {}", activeTransports);
    }

    private ServerTransport newWebSocketTransport()
    {
        try
        {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            loader.loadClass("javax.websocket.server.ServerContainer");
            return newServerTransport("org.cometd.websocket.server.WebSocketTransport");
        }
        catch (Exception x)
        {
            return null;
        }
    }

    private ServerTransport newJSONTransport()
    {
        try
        {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            loader.loadClass("javax.servlet.ReadListener");
            return new AsyncJSONTransport(this);
        }
        catch (Exception x)
        {
            return new JSONTransport(this);
        }
    }

    private ServerTransport newServerTransport(String className)
    {
        try
        {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            @SuppressWarnings("unchecked")
            Class<? extends ServerTransport> klass = (Class<? extends ServerTransport>)loader.loadClass(className);
            Constructor<? extends ServerTransport> constructor = klass.getConstructor(BayeuxServerImpl.class);
            return constructor.newInstance(this);
        }
        catch (Exception x)
        {
            return null;
        }
    }

    public Scheduler.Task schedule(Runnable task, long delay)
    {
        return _scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
    }

    public ChannelId newChannelId(String id)
    {
        ServerChannelImpl channel = _channels.get(id);
        if (channel != null)
            return channel.getChannelId();
        return new ChannelId(id);
    }

    public Map<String, Object> getOptions()
    {
        return _options;
    }

    @ManagedOperation(value = "The value of the given configuration option", impact = "INFO")
    public Object getOption(@Name("optionName") String qualifiedName)
    {
        return _options.get(qualifiedName);
    }

    /**
     * Get an option value as a long
     *
     * @param name The option name
     * @param dft  The default value
     * @return long value
     */
    protected long getOption(String name, long dft)
    {
        Object val = getOption(name);
        if (val == null)
            return dft;
        if (val instanceof Number)
            return ((Number)val).longValue();
        return Long.parseLong(val.toString());
    }

    public Set<String> getOptionNames()
    {
        return _options.keySet();
    }

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
        ServerTransport transport = _currentTransport.get();
        return transport == null ? null : transport.getContext();
    }

    public SecurityPolicy getSecurityPolicy()
    {
        return _policy;
    }

    public MarkedReference<ServerChannel> createChannelIfAbsent(String channelName, Initializer... initializers)
    {
        boolean initialized = false;
        ServerChannelImpl channel = _channels.get(channelName);
        if (channel == null)
        {
            ChannelId channelId = new ChannelId(channelName);
            ServerChannelImpl candidate = new ServerChannelImpl(this, channelId);
            channel = _channels.putIfAbsent(channelName, candidate);
            if (channel == null)
            {
                // My candidate channel was added to the map, so I'd better initialize it

                channel = candidate;
                _logger.debug("Added channel {}", channel);

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
        return new MarkedReference<ServerChannel>(channel, initialized);
    }

    private void notifyConfigureChannel(Initializer listener, ServerChannel channel)
    {
        try
        {
            listener.configureChannel(channel);
        }
        catch (Throwable x)
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
        catch (Throwable x)
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
        if (clientId == null)
            return null;
        return _sessions.get(clientId);
    }

    protected void addServerSession(ServerSessionImpl session, ServerMessage message)
    {
        _sessions.put(session.getId(), session);
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof BayeuxServer.SessionListener)
                notifySessionAdded((SessionListener)listener, session, message);
        }
    }

    private void notifySessionAdded(SessionListener listener, ServerSession session, ServerMessage message)
    {
        try
        {
            listener.sessionAdded(session, message);
        }
        catch (Throwable x)
        {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    /**
     * @param session  the session to remove
     * @param timedOut whether the remove reason is server-side expiration
     * @return true if the session was removed and was connected
     */
    public boolean removeServerSession(ServerSession session, boolean timedOut)
    {
        _logger.debug("Removing session {}, timed out: {}", session, timedOut);

        ServerSessionImpl removed = _sessions.remove(session.getId());

        if (removed != session)
            return false;

        // Invoke BayeuxServer.SessionListener first, so that the application
        // can be "pre-notified" that a session is being removed before the
        // application gets notifications of channel unsubscriptions
        for (BayeuxServerListener listener : _listeners)
        {
            if (listener instanceof SessionListener)
                notifySessionRemoved((SessionListener)listener, session, timedOut);
        }

        return removed.removed(timedOut);
    }

    private void notifySessionRemoved(SessionListener listener, ServerSession session, boolean timedout)
    {
        try
        {
            listener.sessionRemoved(session, timedout);
        }
        catch (Throwable x)
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
        return new LocalSessionImpl(this, idHint);
    }

    public ServerMessage.Mutable newMessage()
    {
        return new ServerMessageImpl();
    }

    public ServerMessage.Mutable newMessage(ServerMessage tocopy)
    {
        ServerMessage.Mutable mutable = newMessage();
        for (String key : tocopy.keySet())
            mutable.put(key, tocopy.get(key));
        return mutable;
    }

    public void setSecurityPolicy(SecurityPolicy securityPolicy)
    {
        _policy = securityPolicy;
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
        return getServerChannel(channelId);
    }

    private ServerChannelImpl getServerChannel(String channelId)
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

    public ServerMessage.Mutable handle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        _logger.debug(">  {} {}", message, session);

        Mutable reply = createReply(message);
        if (!extendRecv(session, message) || session != null && !session.extendRecv(message))
        {
            error(reply, "404::message deleted");
        }
        else
        {
            _logger.debug(">> {}", message);

            String channelName = message.getChannel();

            ServerChannelImpl channel;
            if (channelName == null)
            {
                error(reply, "400::channel missing");
            }
            else
            {
                channel = getServerChannel(channelName);
                if (channel == null)
                {
                    if (session == null)
                    {
                        unknownSession(reply);
                    }
                    else
                    {
                        Authorizer.Result creationResult = isCreationAuthorized(session, message, channelName);
                        if (creationResult instanceof Authorizer.Result.Denied)
                        {
                            String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                            error(reply, "403:" + denyReason + ":create denied");
                        }
                        else
                        {
                            channel = (ServerChannelImpl)createChannelIfAbsent(channelName).getReference();
                        }
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
                            doPublish(session, channel, message);
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

        _logger.debug("<< {}", reply);
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
        List<ServerChannelImpl> channels = new ArrayList<>();
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
        for (ServerChannelImpl channel : channels)
        {
            List<Authorizer> authorizers = channel.authorizers();
            if (!authorizers.isEmpty())
            {
                for (Authorizer authorizer : authorizers)
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

    protected void doPublish(ServerSessionImpl from, ServerChannelImpl to, final ServerMessage.Mutable mutable)
    {
        if (to.isLazy())
            mutable.setLazy(true);

        final List<String> wildChannelNames = to.getChannelId().getWilds();
        final ServerChannelImpl[] wildChannels = new ServerChannelImpl[wildChannelNames.size()];
        for (int i = wildChannelNames.size(); i-- > 0; )
            wildChannels[i] = _channels.get(wildChannelNames.get(i));

        // Call the wild listeners
        for (final ServerChannelImpl wildChannel : wildChannels)
        {
            if (wildChannel == null)
                continue;
            if (wildChannel.isLazy())
                mutable.setLazy(true);
            List<ServerChannelListener> listeners = wildChannel.listeners();
            if (!listeners.isEmpty())
            {
                for (ServerChannelListener listener : listeners)
                    if (listener instanceof MessageListener)
                        if (!notifyOnMessage((MessageListener)listener, from, to, mutable))
                            return;
            }
        }

        // Call the leaf listeners
        List<ServerChannelListener> listeners = to.listeners();
        if (!listeners.isEmpty())
        {
            for (ServerChannelListener listener : listeners)
                if (listener instanceof MessageListener)
                    if (!notifyOnMessage((MessageListener)listener, from, to, mutable))
                        return;
        }

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
            for (final ServerChannelImpl wildChannel : wildChannels)
            {
                if (wildChannel == null)
                    continue;
                Set<ServerSession> subscribers = wildChannel.subscribers();
                if (!subscribers.isEmpty())
                {
                    for (ServerSession session : subscribers)
                    {
                        if (wildSubscribers == null)
                            wildSubscribers = new HashSet<>();
                        if (wildSubscribers.add(session.getId()))
                            ((ServerSessionImpl)session).doDeliver(from, mutable);
                    }
                }
            }
        }

        // Call the leaf subscribers
        Set<ServerSession> subscribers = to.subscribers();
        if (!subscribers.isEmpty())
        {
            for (ServerSession session : subscribers)
            {
                if (wildSubscribers == null || !wildSubscribers.contains(session.getId()))
                    ((ServerSessionImpl)session).doDeliver(from, mutable);
            }
        }

        // Meta handlers
        if (to.isMeta())
        {
            listeners = to.listeners();
            if (!listeners.isEmpty())
            {
                for (ServerChannelListener listener : listeners)
                    if (listener instanceof BayeuxServerImpl.HandlerListener)
                        ((BayeuxServerImpl.HandlerListener)listener).onMessage(from, mutable);
            }
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
        catch (Throwable x)
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
                if (!to.extendSendMeta(reply))
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
        catch (Throwable x)
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
        catch (Throwable x)
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
                    _logger.debug("Extension {} interrupted message processing for {}", extension, message);
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
                    _logger.debug("Extension {} interrupted message processing for {}", extension, message);
                    return false;
                }
            }
        }

        _logger.debug("<  {}", message);
        return true;
    }

    private boolean notifySendMeta(Extension extension, ServerSession to, Mutable message)
    {
        try
        {
            return extension.sendMeta(to, message);
        }
        catch (Throwable x)
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
        catch (Throwable x)
        {
            _logger.info("Exception while invoking extension " + extension, x);
            return true;
        }
    }

    protected boolean removeServerChannel(ServerChannelImpl channel)
    {
        if (_channels.remove(channel.getId(), channel))
        {
            _logger.debug("Removed channel {}", channel);
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
        catch (Throwable x)
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

    public ServerTransport addTransport(ServerTransport transport)
    {
        ServerTransport result = _transports.put(transport.getName(), transport);
        _logger.debug("Added transport {} from {}", transport.getName(), transport.getClass());
        return result;
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

    @SuppressWarnings("ForLoopReplaceableByForEach")
    protected AbstractHttpTransport findHttpTransport(HttpServletRequest request)
    {
        // Avoid allocation of the Iterator
        for (int i = 0; i < _allowedTransports.size(); ++i)
        {
            String transportName = _allowedTransports.get(i);
            ServerTransport serverTransport = getTransport(transportName);
            if (serverTransport instanceof AbstractHttpTransport)
            {
                AbstractHttpTransport transport = (AbstractHttpTransport)serverTransport;
                if (transport.accept(request))
                    return transport;
            }
        }
        return null;
    }

    @ManagedAttribute(value = "The transports allowed by this server", readonly = true)
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
        _logger.debug("setAllowedTransport {} of {}", allowed, _transports);
        _allowedTransports.clear();
        for (String transport : allowed)
        {
            if (_transports.containsKey(transport))
                _allowedTransports.add(transport);
        }
        _logger.debug("allowedTransports {}", _allowedTransports);
    }

    protected void unknownSession(Mutable reply)
    {
        error(reply, "402::Unknown client");
        if (Channel.META_HANDSHAKE.equals(reply.getChannel()) || Channel.META_CONNECT.equals(reply.getChannel()))
        {
            Map<String, Object> advice = reply.getAdvice(true);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
            advice.put(Message.INTERVAL_FIELD, 0L);
        }
    }

    protected void error(ServerMessage.Mutable reply, String error)
    {
        reply.put(Message.ERROR_FIELD, error);
        reply.setSuccessful(false);
    }

    protected ServerMessage.Mutable createReply(ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply = newMessage();
        message.setAssociated(reply);
        reply.setAssociated(message);

        reply.setChannel(message.getChannel());
        String id = message.getId();
        if (id != null)
            reply.setId(id);
        return reply;
    }

    @ManagedOperation(value = "Sweeps channels and sessions of this BayeuxServer", impact = "ACTION")
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

    @ManagedOperation(value = "Dumps the BayeuxServer state", impact = "INFO")
    public String dump()
    {
        StringBuilder b = new StringBuilder();

        ArrayList<Object> children = new ArrayList<>();
        if (_policy != null)
            children.add(_policy);

        for (ServerChannelImpl channel : _channels.values())
        {
            if (channel.getChannelId().depth() == 1)
                children.add(channel);
        }

        int leaves = children.size();
        int i = 0;
        for (Object child : children)
        {
            b.append(" +-");
            if (child instanceof ServerChannelImpl)
                ((ServerChannelImpl)child).dump(b, ((++i == leaves) ? "   " : " | "));
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
                Object[] array = (Object[])channels;
                List<String> channelList = new ArrayList<>();
                for (Object o : array)
                    channelList.add(String.valueOf(o));
                return channelList;
            }

            if (channels instanceof List)
            {
                List<?> list = (List<?>)channels;
                List<String> channelList = new ArrayList<>();
                for (Object o : list)
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

            BayeuxContext context = getContext();
            if (context != null)
                session.setUserAgent(context.getHeader("User-Agent"));

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
            addServerSession(session, message);

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

            session.connected();

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
            Map<String, Object> adviceOut = session.takeAdvice(getCurrentTransport());
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
            if (subscriptions == null)
            {
                error(reply, "403::subscription_invalid");
                return;
            }

            for (String subscription : subscriptions)
            {
                ServerChannelImpl channel = getServerChannel(subscription);
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
                        channel = (ServerChannelImpl)createChannelIfAbsent(subscription).getReference();
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
                            if (channel.subscribe(from, message))
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
            if (subscriptions == null)
            {
                error(reply, "403::subscription_invalid");
                return;
            }

            for (String subscription : subscriptions)
            {
                ServerChannelImpl channel = getServerChannel(subscription);
                if (channel == null)
                {
                    error(reply, "400::channel_missing");
                    break;
                }
                else
                {
                    if (channel.unsubscribe(from, message))
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

            reply.setSuccessful(true);
            removeServerSession(session, false);
            // Wake up the possibly pending /meta/connect
            session.flush();
        }
    }
}
