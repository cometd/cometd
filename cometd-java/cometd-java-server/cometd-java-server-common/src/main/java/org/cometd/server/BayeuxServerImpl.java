/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel.Initializer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerChannel.MessageListener;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.AsyncFoldLeft;
import org.cometd.server.http.AbstractHttpTransport;
import org.cometd.server.http.AsyncJSONTransport;
import org.cometd.server.http.JSONPTransport;
import org.cometd.server.http.JSONTransport;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("The CometD server")
public class BayeuxServerImpl extends AbstractLifeCycle implements BayeuxServer, Dumpable {
    private static final boolean[] VALID = new boolean[256];

    static {
        VALID[' '] = true;
        VALID['!'] = true;
        VALID['#'] = true;
        VALID['$'] = true;
        VALID['('] = true;
        VALID[')'] = true;
        VALID['*'] = true;
        VALID['+'] = true;
        VALID['-'] = true;
        VALID['.'] = true;
        VALID['/'] = true;
        VALID['@'] = true;
        VALID['_'] = true;
        VALID['{'] = true;
        VALID['~'] = true;
        VALID['}'] = true;
        for (int i = '0'; i <= '9'; ++i) {
            VALID[i] = true;
        }
        for (int i = 'A'; i <= 'Z'; ++i) {
            VALID[i] = true;
        }
        for (int i = 'a'; i <= 'z'; ++i) {
            VALID[i] = true;
        }
    }

    public static final String ALLOWED_TRANSPORTS_OPTION = "allowedTransports";
    public static final String SWEEP_PERIOD_OPTION = "sweepPeriod";
    public static final String TRANSPORTS_OPTION = "transports";
    public static final String VALIDATE_MESSAGE_FIELDS_OPTION = "validateMessageFields";
    public static final String BROADCAST_TO_PUBLISHER_OPTION = "broadcastToPublisher";

    private final Logger _logger = LoggerFactory.getLogger(getClass().getName() + "." + Integer.toHexString(System.identityHashCode(this)));
    private final SecureRandom _random = new SecureRandom();
    private final List<BayeuxServerListener> _listeners = new CopyOnWriteArrayList<>();
    private final List<Extension> _extensions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ServerSessionImpl> _sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerChannelImpl> _channels = new ConcurrentHashMap<>();
    private final Map<String, ServerTransport> _transports = new LinkedHashMap<>(); // Order is important
    private final List<String> _allowedTransports = new ArrayList<>();
    private final Map<String, Object> _options = new TreeMap<>();
    private final Scheduler _scheduler = new ScheduledExecutorScheduler("BayeuxServer@" + Integer.toHexString(hashCode()) + "-Scheduler", false);
    private SecurityPolicy _policy = new DefaultSecurityPolicy();
    private JSONContextServer _jsonContext;
    private boolean _validation;
    private boolean _broadcastToPublisher;
    private boolean _detailedDump;

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        initializeMetaChannels();
        initializeJSONContext();
        initializeServerTransports();

        _scheduler.start();

        long defaultSweepPeriod = 997;
        long sweepPeriodOption = getOption(SWEEP_PERIOD_OPTION, defaultSweepPeriod);
        if (sweepPeriodOption < 0) {
            sweepPeriodOption = defaultSweepPeriod;
        }
        final long sweepPeriod = sweepPeriodOption;
        schedule(new Runnable() {
            @Override
            public void run() {
                sweep();
                schedule(this, sweepPeriod);
            }
        }, sweepPeriod);

        _validation = getOption(VALIDATE_MESSAGE_FIELDS_OPTION, true);
        _broadcastToPublisher = getOption(BROADCAST_TO_PUBLISHER_OPTION, true);
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();

        for (String allowedTransportName : getAllowedTransports()) {
            ServerTransport transport = getTransport(allowedTransportName);
            if (transport instanceof AbstractServerTransport) {
                ((AbstractServerTransport)transport).destroy();
            }
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

    protected void initializeMetaChannels() {
        createChannelIfAbsent(Channel.META_HANDSHAKE);
        createChannelIfAbsent(Channel.META_CONNECT);
        createChannelIfAbsent(Channel.META_SUBSCRIBE);
        createChannelIfAbsent(Channel.META_UNSUBSCRIBE);
        createChannelIfAbsent(Channel.META_DISCONNECT);
    }

    protected void initializeJSONContext() throws Exception {
        Object option = getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
        if (option == null) {
            _jsonContext = new JettyJSONContextServer();
        } else {
            if (option instanceof String) {
                Class<?> jsonContextClass = Thread.currentThread().getContextClassLoader().loadClass((String)option);
                if (JSONContextServer.class.isAssignableFrom(jsonContextClass)) {
                    _jsonContext = (JSONContextServer)jsonContextClass.getConstructor().newInstance();
                } else {
                    throw new IllegalArgumentException("Invalid " + JSONContextServer.class.getName() + " implementation class");
                }
            } else if (option instanceof JSONContextServer) {
                _jsonContext = (JSONContextServer)option;
            } else {
                throw new IllegalArgumentException("Invalid " + JSONContextServer.class.getName() + " implementation class");
            }
        }
        _options.put(AbstractServerTransport.JSON_CONTEXT_OPTION, _jsonContext);
    }

    protected void initializeServerTransports() {
        if (_transports.isEmpty()) {
            String option = (String)getOption(TRANSPORTS_OPTION);
            if (option == null) {
                // Order is important, see #findHttpTransport()
                ServerTransport transport = newWebSocketTransport();
                if (transport != null) {
                    addTransport(transport);
                }
                addTransport(newJSONTransport());
                addTransport(new JSONPTransport(this));
            } else {
                for (String className : option.split(",")) {
                    ServerTransport transport = newServerTransport(className.trim());
                    if (transport != null) {
                        addTransport(transport);
                    }
                }

                if (_transports.isEmpty()) {
                    throw new IllegalArgumentException("Option '" + TRANSPORTS_OPTION +
                            "' does not contain a valid list of server transport class names");
                }
            }
        }

        if (_allowedTransports.isEmpty()) {
            String option = (String)getOption(ALLOWED_TRANSPORTS_OPTION);
            if (option == null) {
                _allowedTransports.addAll(_transports.keySet());
            } else {
                for (String transportName : option.split(",")) {
                    if (_transports.containsKey(transportName)) {
                        _allowedTransports.add(transportName);
                    }
                }

                if (_allowedTransports.isEmpty()) {
                    throw new IllegalArgumentException("Option '" + ALLOWED_TRANSPORTS_OPTION +
                            "' does not contain at least one configured server transport name");
                }
            }
        }

        List<String> activeTransports = new ArrayList<>();
        for (String transportName : _allowedTransports) {
            ServerTransport serverTransport = getTransport(transportName);
            if (serverTransport instanceof AbstractServerTransport) {
                ((AbstractServerTransport)serverTransport).init();
                activeTransports.add(serverTransport.getName());
            }
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("Active transports: {}", activeTransports);
        }
    }

    private ServerTransport newWebSocketTransport() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            loader.loadClass("jakarta.websocket.server.ServerContainer");
            String transportClass = "org.cometd.server.websocket.javax.WebSocketTransport";
            ServerTransport transport = newServerTransport(transportClass);
            if (transport == null) {
                _logger.info("JSR 356 WebSocket classes available, but " + transportClass +
                        " unavailable: JSR 356 WebSocket transport disabled");
            }
            return transport;
        } catch (Exception x) {
            return null;
        }
    }

    private ServerTransport newJSONTransport() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            loader.loadClass("jakarta.servlet.ReadListener");
            return new AsyncJSONTransport(this);
        } catch (Exception x) {
            return new JSONTransport(this);
        }
    }

    private ServerTransport newServerTransport(String className) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            @SuppressWarnings("unchecked")
            Class<? extends ServerTransport> klass = (Class<? extends ServerTransport>)loader.loadClass(className);
            Constructor<? extends ServerTransport> constructor = klass.getConstructor(BayeuxServerImpl.class);
            return constructor.newInstance(this);
        } catch (Exception x) {
            return null;
        }
    }

    /**
     * <p>Entry point to schedule tasks in CometD.</p>
     * <p>Subclasses may override and run the task in a {@link java.util.concurrent.Executor},
     * rather than in the scheduler thread.</p>
     *
     * @param task  the task to schedule
     * @param delay the delay, in milliseconds, to run the task
     * @return the task promise
     */
    public Scheduler.Task schedule(Runnable task, long delay) {
        return _scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
    }

    public ChannelId newChannelId(String id) {
        ServerChannelImpl channel = _channels.get(id);
        if (channel != null) {
            return channel.getChannelId();
        }
        return new ChannelId(id);
    }

    public Map<String, Object> getOptions() {
        return _options;
    }

    @Override
    @ManagedOperation(value = "The value of the given configuration option", impact = "INFO")
    public Object getOption(@Name("optionName") String qualifiedName) {
        return _options.get(qualifiedName);
    }

    protected long getOption(String name, long dft) {
        Object val = getOption(name);
        if (val == null) {
            return dft;
        }
        if (val instanceof Number) {
            return ((Number)val).longValue();
        }
        return Long.parseLong(val.toString());
    }

    protected boolean getOption(String name, boolean dft) {
        Object value = getOption(name);
        if (value == null) {
            return dft;
        }
        if (value instanceof Boolean) {
            return (Boolean)value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    public Set<String> getOptionNames() {
        return _options.keySet();
    }

    @Override
    public void setOption(String qualifiedName, Object value) {
        _options.put(qualifiedName, value);
    }

    public void setOptions(Map<String, Object> options) {
        _options.putAll(options);
    }

    public long randomLong() {
        long value = _random.nextLong();
        return value < 0 ? -value : value;
    }

    @Override
    public SecurityPolicy getSecurityPolicy() {
        return _policy;
    }

    public JSONContextServer getJSONContext() {
        return _jsonContext;
    }

    @Override
    public MarkedReference<ServerChannel> createChannelIfAbsent(String channelName, Initializer... initializers) {
        ChannelId channelId;
        boolean initialized = false;
        ServerChannelImpl channel = _channels.get(channelName);
        if (channel == null) {
            // Creating the ChannelId will also normalize the channelName.
            channelId = new ChannelId(channelName);
            String id = channelId.getId();
            if (!id.equals(channelName)) {
                channelName = id;
                channel = _channels.get(channelName);
            }
        } else {
            channelId = channel.getChannelId();
        }

        if (channel == null) {
            ServerChannelImpl candidate = new ServerChannelImpl(this, channelId);
            channel = _channels.putIfAbsent(channelName, candidate);
            if (channel == null) {
                // My candidate channel was added to the map, so I'd better initialize it

                channel = candidate;
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Added channel {}", channel);
                }

                try {
                    for (Initializer initializer : initializers) {
                        notifyConfigureChannel(initializer, channel);
                    }

                    for (BayeuxServer.BayeuxServerListener listener : _listeners) {
                        if (listener instanceof ServerChannel.Initializer) {
                            notifyConfigureChannel((Initializer)listener, channel);
                        }
                    }
                } finally {
                    channel.initialized();
                }

                for (BayeuxServer.BayeuxServerListener listener : _listeners) {
                    if (listener instanceof BayeuxServer.ChannelListener) {
                        notifyChannelAdded((ChannelListener)listener, channel);
                    }
                }

                initialized = true;
            }
        } else {
            channel.resetSweeperPasses();
            // Double check if the sweeper removed this channel between the check at the top and here.
            // This is not 100% fool proof (e.g. this thread is preempted long enough for the sweeper
            // to remove the channel, but the alternative is to have a global lock)
            _channels.putIfAbsent(channelName, channel);
        }
        // Another thread may add this channel concurrently, so wait until it is initialized
        channel.waitForInitialized();
        return new MarkedReference<>(channel, initialized);
    }

    private void notifyConfigureChannel(Initializer listener, ServerChannel channel) {
        try {
            listener.configureChannel(channel);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    private void notifyChannelAdded(ChannelListener listener, ServerChannel channel) {
        try {
            listener.channelAdded(channel);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    @Override
    public List<ServerSession> getSessions() {
        return Collections.unmodifiableList(new ArrayList<ServerSession>(_sessions.values()));
    }

    @Override
    public ServerSession getSession(String clientId) {
        return clientId == null ? null : _sessions.get(clientId);
    }

    protected void addServerSession(ServerSessionImpl session, ServerMessage message) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Adding session {}", session);
        }
        _sessions.put(session.getId(), session);
        for (BayeuxServerListener listener : _listeners) {
            if (listener instanceof BayeuxServer.SessionListener) {
                notifySessionAdded((SessionListener)listener, session, message);
            }
        }
        session.added(message);
    }

    private void notifySessionAdded(SessionListener listener, ServerSession session, ServerMessage message) {
        try {
            listener.sessionAdded(session, message);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    @Override
    public boolean removeSession(ServerSession session) {
        return removeServerSession(session, null, false).getReference() != null;
    }

    /**
     * @param session the session to remove
     * @param timeout whether the session has been removed due to a timeout
     * @return true if the session was removed and was connected
     */
    public boolean removeServerSession(ServerSession session, boolean timeout) {
        return removeServerSession(session, null, timeout).isMarked();
    }

    private MarkedReference<ServerSessionImpl> removeServerSession(ServerSession session, ServerMessage message, boolean timeout) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Removing session timeout: {}, {}, message: {}", timeout, session, message);
        }

        ServerSessionImpl removed = _sessions.remove(session.getId());

        if (removed != session) {
            return MarkedReference.empty();
        }

        // Invoke BayeuxServer.SessionListener first, so that the application
        // can be "pre-notified" that a session is being removed before the
        // application gets notifications of channel unsubscriptions.
        for (BayeuxServerListener listener : _listeners) {
            if (listener instanceof SessionListener) {
                notifySessionRemoved((SessionListener)listener, removed, message, timeout);
            }
        }

        boolean connected = removed.removed(message, timeout);

        return new MarkedReference<>(removed, connected);
    }

    private void notifySessionRemoved(SessionListener listener, ServerSession session, ServerMessage message, boolean timeout) {
        try {
            listener.sessionRemoved(session, message, timeout);
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    public ServerSessionImpl newServerSession() {
        return new ServerSessionImpl(this);
    }

    @Override
    public LocalSession newLocalSession(String idHint) {
        return new LocalSessionImpl(this, idHint);
    }

    @Override
    public ServerMessage.Mutable newMessage() {
        return new ServerMessageImpl();
    }

    public ServerMessage.Mutable newMessage(ServerMessage original) {
        ServerMessage.Mutable mutable = newMessage();
        mutable.putAll(original);
        return mutable;
    }

    @Override
    public void setSecurityPolicy(SecurityPolicy securityPolicy) {
        _policy = securityPolicy;
    }

    @Override
    public void addExtension(Extension extension) {
        _extensions.add(extension);
    }

    @Override
    public void removeExtension(Extension extension) {
        _extensions.remove(extension);
    }

    @Override
    public List<Extension> getExtensions() {
        return Collections.unmodifiableList(_extensions);
    }

    @Override
    public void addListener(BayeuxServerListener listener) {
        Objects.requireNonNull(listener);
        _listeners.add(listener);
    }

    @Override
    public ServerChannel getChannel(String channelId) {
        return getServerChannel(channelId);
    }

    private ServerChannelImpl getServerChannel(String channelId) {
        ServerChannelImpl channel = _channels.get(channelId);
        if (channel != null) {
            channel.waitForInitialized();
        }
        return channel;
    }

    @Override
    public List<ServerChannel> getChannels() {
        List<ServerChannel> result = new ArrayList<>();
        for (ServerChannelImpl channel : _channels.values()) {
            channel.waitForInitialized();
            result.add(channel);
        }
        return result;
    }

    @Override
    public void removeListener(BayeuxServerListener listener) {
        _listeners.remove(listener);
    }

    public void handle(ServerSessionImpl session, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
        ServerMessageImpl reply = (ServerMessageImpl)createReply(message);
        if (_validation) {
            String error = validateMessage(message);
            if (error != null) {
                error(reply, error);
                promise.succeed(reply);
                return;
            }
        }
        extendIncoming(session, message, Promise.from(extPass -> {
            if (extPass) {
                if (session != null) {
                    session.extendIncoming(message, Promise.from(sessExtPass -> {
                        if (sessExtPass) {
                            handle1(session, message, promise);
                        } else {
                            if (!reply.isHandled()) {
                                error(reply, "404::message_deleted");
                            }
                            promise.succeed(reply);
                        }
                    }, promise::fail));
                } else {
                    handle1(null, message, promise);
                }
            } else {
                if (!reply.isHandled()) {
                    error(reply, "404::message_deleted");
                }
                promise.succeed(reply);
            }
        }, promise::fail));
    }

    private void handle1(ServerSessionImpl session, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
        if (_logger.isDebugEnabled()) {
            _logger.debug(">  {} {}", message, session);
        }

        ServerMessage.Mutable reply = message.getAssociated();
        if (session == null || session.isDisconnected() ||
                (!session.getId().equals(message.getClientId()) && !Channel.META_HANDSHAKE.equals(message.getChannel()))) {
            unknownSession(reply);
            promise.succeed(reply);
        } else {
            String channelName = message.getChannel();
            session.cancelExpiration(Channel.META_CONNECT.equals(channelName));

            if (channelName == null) {
                error(reply, "400::channel_missing");
                promise.succeed(reply);
            } else {
                ServerChannelImpl channel = getServerChannel(channelName);
                if (channel == null) {
                    isCreationAuthorized(session, message, channelName, Promise.from(result -> {
                        if (result instanceof Authorizer.Result.Denied) {
                            String denyReason = ((Authorizer.Result.Denied)result).getReason();
                            error(reply, "403:" + denyReason + ":channel_create_denied");
                            promise.succeed(reply);
                        } else {
                            handle2(session, message, (ServerChannelImpl)createChannelIfAbsent(channelName).getReference(), promise);
                        }
                    }, promise::fail));
                } else {
                    handle2(session, message, channel, promise);
                }
            }
        }
    }

    private void handle2(ServerSessionImpl session, ServerMessage.Mutable message, ServerChannelImpl channel, Promise<ServerMessage.Mutable> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        if (channel.isMeta()) {
            publish(session, channel, message, true, Promise.from(published -> promise.succeed(reply), promise::fail));
        } else {
            isPublishAuthorized(channel, session, message, Promise.from(result -> {
                if (result instanceof Authorizer.Result.Denied) {
                    String denyReason = ((Authorizer.Result.Denied)result).getReason();
                    error(reply, "403:" + denyReason + ":publish_denied");
                    promise.succeed(reply);
                } else {
                    reply.setSuccessful(true);
                    publish(session, channel, message, true, Promise.from(published -> promise.succeed(reply), promise::fail));
                }
            }, promise::fail));
        }
    }

    protected String validateMessage(Mutable message) {
        String channel = message.getChannel();
        if (channel == null) {
            return "400::channel_missing";
        }
        if (!validate(channel)) {
            return "405::channel_invalid";
        }
        String id = message.getId();
        if (id != null && !validate(id)) {
            return "405::message_id_invalid";
        }
        return null;
    }

    private boolean validate(String value) {
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c > 127 || !VALID[c]) {
                return false;
            }
        }
        return true;
    }

    private void isPublishAuthorized(ServerChannel channel, ServerSession session, ServerMessage message, Promise<Authorizer.Result> promise) {
        if (_policy != null) {
            _policy.canPublish(this, session, channel, message, Promise.from(can -> {
                if (can == null || can) {
                    isOperationAuthorized(Authorizer.Operation.PUBLISH, session, message, channel.getChannelId(), promise);
                } else {
                    _logger.info("{} denied publish on channel {} by {}", session, channel.getId(), _policy);
                    promise.succeed(Authorizer.Result.deny("denied_by_security_policy"));
                }
            }, promise::fail));
        } else {
            isOperationAuthorized(Authorizer.Operation.PUBLISH, session, message, channel.getChannelId(), promise);
        }
    }

    private void isSubscribeAuthorized(ServerChannel channel, ServerSession session, ServerMessage message, Promise<Authorizer.Result> promise) {
        if (_policy != null) {
            _policy.canSubscribe(this, session, channel, message, Promise.from(can -> {
                if (can == null || can) {
                    isOperationAuthorized(Authorizer.Operation.SUBSCRIBE, session, message, channel.getChannelId(), promise);
                } else {
                    _logger.info("{} denied Subscribe@{} by {}", session, channel, _policy);
                    promise.succeed(Authorizer.Result.deny("denied_by_security_policy"));
                }
            }, promise::fail));
        } else {
            isOperationAuthorized(Authorizer.Operation.SUBSCRIBE, session, message, channel.getChannelId(), promise);
        }
    }

    private void isCreationAuthorized(ServerSession session, ServerMessage message, String channel, Promise<Authorizer.Result> promise) {
        if (_policy != null) {
            _policy.canCreate(BayeuxServerImpl.this, session, channel, message, Promise.from(can -> {
                if (can == null || can) {
                    isOperationAuthorized(Authorizer.Operation.CREATE, session, message, new ChannelId(channel), promise);
                } else {
                    _logger.info("{} denied creation of channel {} by {}", session, channel, _policy);
                    promise.succeed(Authorizer.Result.deny("denied_by_security_policy"));
                }
            }, promise::fail));
        } else {
            isOperationAuthorized(Authorizer.Operation.CREATE, session, message, new ChannelId(channel), promise);
        }
    }

    private void isOperationAuthorized(Authorizer.Operation operation, ServerSession session, ServerMessage message, ChannelId channelId, Promise<Authorizer.Result> promise) {
        isChannelOperationAuthorized(operation, session, message, channelId, Promise.from(result -> {
            if (result == null) {
                result = Authorizer.Result.grant();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("No authorizers, {} for channel {} {}", operation, channelId, result);
                }
            } else {
                if (result.isGranted()) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("No authorizer denied {} for channel {}, authorization {}", operation, channelId, result);
                    }
                } else if (!result.isDenied()) {
                    result = Authorizer.Result.deny("denied_by_not_granting");
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("No authorizer granted {} for channel {}, authorization {}", operation, channelId, result);
                    }
                }
            }
            promise.succeed(result);
        }, promise::fail));
    }

    private void isChannelOperationAuthorized(Authorizer.Operation operation, ServerSession session, ServerMessage message, ChannelId channelId, Promise<Authorizer.Result> promise) {
        List<String> channels = new ArrayList<>(channelId.getWilds());
        channels.add(channelId.getId());
        AsyncFoldLeft.run(channels, null, (result, channelName, loop) -> {
            ServerChannelImpl channel = _channels.get(channelName);
            if (channel != null) {
                isChannelOperationAuthorized(channel, operation, session, message, channelId, Promise.from(authz -> {
                    if (authz != null) {
                        if (authz.isDenied()) {
                            loop.leave(authz);
                        } else if (result == null || authz.isGranted()) {
                            loop.proceed(authz);
                        } else {
                            loop.proceed(result);
                        }
                    } else {
                        loop.proceed(result);
                    }
                }, promise::fail));
            } else {
                loop.proceed(result);
            }
        }, promise);
    }

    private void isChannelOperationAuthorized(ServerChannelImpl channel, Authorizer.Operation operation, ServerSession session, ServerMessage message, ChannelId channelId, Promise<Authorizer.Result> promise) {
        List<Authorizer> authorizers = channel.authorizers();
        if (authorizers.isEmpty()) {
            promise.succeed(null);
        } else {
            AsyncFoldLeft.run(authorizers, Authorizer.Result.ignore(), (result, authorizer, loop) ->
                    authorizer.authorize(operation, channelId, session, message, Promise.from(authorization -> {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Authorizer {} on channel {} {} {} for channel {}", authorizer, channel, authorization, operation, channelId);
                        }
                        if (authorization.isDenied()) {
                            loop.leave(authorization);
                        } else if (authorization.isGranted()) {
                            loop.proceed(authorization);
                        } else {
                            loop.proceed(result);
                        }
                    }, promise::fail)), promise);
        }
    }

    protected void publish(ServerSessionImpl session, ServerChannelImpl channel, ServerMessage.Mutable message, boolean receiving, Promise<Boolean> promise) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("<  {} {}", message, session);
        }
        if (channel.isBroadcast()) {
            // Do not leak the clientId to other subscribers
            // as we are now "sending" this message.
            message.setClientId(null);
            // Reset the messageId to avoid clashes with message-based transports such
            // as websocket whose clients may rely on the messageId to match request/responses.
            message.setId(null);
        }

        notifyListeners(session, channel, message, Promise.from(proceed -> {
            if (proceed) {
                publish1(session, channel, message, receiving, promise);
            } else {
                ServerMessageImpl reply = (ServerMessageImpl)message.getAssociated();
                if (reply != null && !reply.isHandled()) {
                    error(reply, "404::message_deleted");
                }
                promise.succeed(false);
            }
        }, promise::fail));
    }

    private void publish1(ServerSessionImpl session, ServerChannelImpl channel, ServerMessage.Mutable message, boolean receiving, Promise<Boolean> promise) {
        if (channel.isBroadcast() || !receiving) {
            extendOutgoing(session, null, message, Promise.from(result -> {
                if (result) {
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
                    freeze(message);
                    publish2(session, channel, message, promise);
                } else {
                    ServerMessage.Mutable reply = message.getAssociated();
                    error(reply, "404::message_deleted");
                    promise.succeed(false);
                }
            }, promise::fail));
        } else {
            publish2(session, channel, message, promise);
        }
    }

    private void publish2(ServerSessionImpl session, ServerChannelImpl channel, ServerMessage.Mutable message, Promise<Boolean> promise) {
        if (channel.isMeta()) {
            notifyMetaHandlers(session, channel, message, promise);
        } else if (channel.isBroadcast()) {
            notifySubscribers(session, channel, message, promise);
        } else {
            promise.succeed(true);
        }
    }

    private void notifySubscribers(ServerSessionImpl session, ServerChannelImpl channel, Mutable message, Promise<Boolean> promise) {
        Set<String> wildSubscribers = new HashSet<>();
        AsyncFoldLeft.run(channel.getChannelId().getWilds(), true, (result, wildName, wildLoop) -> {
                    ServerChannelImpl wildChannel = _channels.get(wildName);
                    if (wildChannel == null) {
                        wildLoop.proceed(result);
                    } else {
                        Set<ServerSession> subscribers = wildChannel.subscribers();
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Notifying {} subscribers on {}", subscribers.size(), wildChannel);
                        }
                        AsyncFoldLeft.run(subscribers, true, (r, subscriber, loop) -> {
                            if (wildSubscribers.add(subscriber.getId())) {
                                if (subscriber == session && !channel.isBroadcastToPublisher()) {
                                    loop.proceed(true);
                                } else {
                                    ((ServerSessionImpl)subscriber).deliver1(session, message, Promise.from(b -> loop.proceed(true), loop::fail));
                                }
                            } else {
                                loop.proceed(r);
                            }
                        }, Promise.from(y -> wildLoop.proceed(true), wildLoop::fail));
                    }
                }, Promise.from(b -> {
                    Set<ServerSession> subscribers = channel.subscribers();
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Notifying {} subscribers on {}", subscribers.size(), channel);
                    }
                    AsyncFoldLeft.run(subscribers, true, (result, subscriber, loop) -> {
                        if (!wildSubscribers.contains(subscriber.getId())) {
                            if (subscriber == session && !channel.isBroadcastToPublisher()) {
                                loop.proceed(true);
                            } else {
                                ((ServerSessionImpl)subscriber).deliver1(session, message, Promise.from(y -> loop.proceed(true), loop::fail));
                            }
                        } else {
                            loop.proceed(true);
                        }
                    }, promise);
                }, promise::fail)
        );
    }

    private void notifyListeners(ServerSessionImpl session, ServerChannelImpl channel, Mutable message, Promise<Boolean> promise) {
        List<String> channels = new ArrayList<>(channel.getChannelId().getWilds());
        channels.add(channel.getId());
        AsyncFoldLeft.run(channels, true, (channelResult, channelName, channelLoop) -> {
            ServerChannelImpl target = _channels.get(channelName);
            if (target == null) {
                channelLoop.proceed(channelResult);
            } else {
                if (target.isLazy()) {
                    message.setLazy(true);
                }
                List<ConfigurableServerChannel.ServerChannelListener> listeners = target.listeners();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Notifying {} listeners on {}", listeners.size(), target);
                }
                AsyncFoldLeft.run(listeners, true, (result, listener, loop) -> {
                    if (listener instanceof MessageListener) {
                        notifyOnMessage((MessageListener)listener, session, channel, message, resolveLoop(loop));
                    } else {
                        loop.proceed(true);
                    }
                }, resolveLoop(channelLoop));
            }
        }, promise);
    }

    protected Promise<Boolean> resolveLoop(AsyncFoldLeft.Loop<Boolean> loop) {
        return Promise.from(result -> {
            if (result) {
                loop.proceed(true);
            } else {
                loop.leave(false);
            }
        }, loop::fail);
    }

    private void notifyMetaHandlers(ServerSessionImpl session, ServerChannelImpl channel, Mutable message, Promise<Boolean> promise) {
        switch (channel.getId()) {
            case Channel.META_HANDSHAKE:
                handleMetaHandshake(session, message, promise);
                break;
            case Channel.META_CONNECT:
                handleMetaConnect(session, message, promise);
                break;
            case Channel.META_SUBSCRIBE:
                handleMetaSubscribe(session, message, promise);
                break;
            case Channel.META_UNSUBSCRIBE:
                handleMetaUnsubscribe(session, message, promise);
                break;
            case Channel.META_DISCONNECT:
                handleMetaDisconnect(session, message, promise);
                break;
            default:
                promise.fail(new IllegalStateException("Invalid channel " + channel));
                break;
        }
    }

    public void freeze(Mutable mutable) {
        if (mutable instanceof ServerMessageImpl) {
            ServerMessageImpl message = (ServerMessageImpl)mutable;
            if (message.isFrozen()) {
                return;
            }
            String json = _jsonContext.generate(message);
            message.freeze(json);
        }
    }

    private void notifyOnMessage(MessageListener listener, ServerSession from, ServerChannel to, Mutable mutable, Promise<Boolean> promise) {
        try {
            listener.onMessage(from, to, mutable, Promise.from(r -> promise.succeed(r == null || r), failure -> {
                _logger.info("Exception reported by listener " + listener, failure);
                promise.succeed(true);
            }));
        } catch (Throwable x) {
            _logger.info("Exception thrown by listener " + listener, x);
            promise.succeed(true);
        }
    }

    private void extendIncoming(ServerSessionImpl session, ServerMessage.Mutable message, Promise<Boolean> promise) {
        AsyncFoldLeft.run(_extensions, true, (result, extension, loop) -> {
            if (result) {
                try {
                    extension.incoming(session, message, Promise.from(r -> {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Extension {}: result {} for incoming message {}", extension, r, message);
                        }
                        loop.proceed(r == null || r);
                    }, failure -> {
                        _logger.info("Exception reported by extension " + extension, failure);
                        loop.proceed(true);
                    }));
                } catch (Throwable x) {
                    _logger.info("Exception thrown by extension " + extension, x);
                    loop.proceed(true);
                }
            } else {
                loop.leave(false);
            }
        }, promise);
    }

    protected void extendOutgoing(ServerSession sender, ServerSession session, Mutable message, Promise<Boolean> promise) {
        List<Extension> extensions = new ArrayList<>(_extensions);
        Collections.reverse(extensions);
        AsyncFoldLeft.run(extensions, true, (result, extension, loop) -> {
            if (result) {
                try {
                    extension.outgoing(sender, session, message, Promise.from(r -> loop.proceed(r == null || r), failure -> {
                        _logger.info("Exception reported by extension " + extension, failure);
                        loop.proceed(true);
                    }));
                } catch (Exception x) {
                    _logger.info("Exception thrown by extension " + extension, x);
                    loop.proceed(true);
                }
            } else {
                loop.leave(false);
            }
        }, promise);
    }

    public void extendReply(ServerSessionImpl sender, ServerSessionImpl session, ServerMessage.Mutable reply, Promise<ServerMessage.Mutable> promise) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("<< {} {}", reply, sender);
        }
        extendOutgoing(sender, session, reply, Promise.from(b -> {
            if (b) {
                if (session != null) {
                    session.extendOutgoing(sender, reply, promise);
                } else {
                    promise.succeed(reply);
                }
            } else {
                promise.succeed(null);
            }
        }, promise::fail));
    }

    protected boolean removeServerChannel(ServerChannelImpl channel) {
        if (_channels.remove(channel.getId(), channel)) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Removed channel {}", channel);
            }
            for (BayeuxServerListener listener : _listeners) {
                if (listener instanceof BayeuxServer.ChannelListener) {
                    notifyChannelRemoved((ChannelListener)listener, channel);
                }
            }
            return true;
        }
        return false;
    }

    private void notifyChannelRemoved(ChannelListener listener, ServerChannelImpl channel) {
        try {
            listener.channelRemoved(channel.getId());
        } catch (Throwable x) {
            _logger.info("Exception while invoking listener " + listener, x);
        }
    }

    protected List<BayeuxServerListener> getListeners() {
        return Collections.unmodifiableList(_listeners);
    }

    @Override
    public Set<String> getKnownTransportNames() {
        return _transports.keySet();
    }

    @Override
    public ServerTransport getTransport(String transport) {
        return _transports.get(transport);
    }

    public ServerTransport addTransport(ServerTransport transport) {
        ServerTransport result = _transports.put(transport.getName(), transport);
        if (_logger.isDebugEnabled()) {
            _logger.debug("Added transport {} from {}", transport.getName(), transport.getClass());
        }
        return result;
    }

    public void setTransports(ServerTransport... transports) {
        setTransports(Arrays.asList(transports));
    }

    public void setTransports(List<ServerTransport> transports) {
        _transports.clear();
        for (ServerTransport transport : transports) {
            addTransport(transport);
        }
    }

    public List<ServerTransport> getTransports() {
        return new ArrayList<>(_transports.values());
    }

    protected AbstractHttpTransport findHttpTransport(HttpServletRequest request) {
        for (String transportName : _allowedTransports) {
            ServerTransport serverTransport = getTransport(transportName);
            if (serverTransport instanceof AbstractHttpTransport) {
                AbstractHttpTransport transport = (AbstractHttpTransport)serverTransport;
                if (transport.accept(request)) {
                    return transport;
                }
            }
        }
        return null;
    }

    @ManagedAttribute(value = "The transports allowed by this server", readonly = true)
    @Override
    public List<String> getAllowedTransports() {
        return Collections.unmodifiableList(_allowedTransports);
    }

    public void setAllowedTransports(String... allowed) {
        setAllowedTransports(Arrays.asList(allowed));
    }

    public void setAllowedTransports(List<String> allowed) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("setAllowedTransport {} of {}", allowed, _transports);
        }
        _allowedTransports.clear();
        for (String transport : allowed) {
            if (_transports.containsKey(transport)) {
                _allowedTransports.add(transport);
            }
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("allowedTransports {}", _allowedTransports);
        }
    }

    @ManagedAttribute(value = "Whether this server broadcast messages to the publisher", readonly = true)
    public boolean isBroadcastToPublisher() {
        return _broadcastToPublisher;
    }

    protected void unknownSession(Mutable reply) {
        error(reply, "402::session_unknown");
        if (Channel.META_HANDSHAKE.equals(reply.getChannel()) || Channel.META_CONNECT.equals(reply.getChannel())) {
            Map<String, Object> advice = reply.getAdvice(true);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
            advice.put(Message.INTERVAL_FIELD, 0L);
        }
    }

    protected void error(ServerMessage.Mutable reply, String error) {
        if (reply != null) {
            reply.put(Message.ERROR_FIELD, error);
            reply.setSuccessful(false);
        }
    }

    protected ServerMessage.Mutable createReply(ServerMessage.Mutable message) {
        ServerMessageImpl reply = (ServerMessageImpl)newMessage();
        message.setAssociated(reply);
        reply.setAssociated(message);
        reply.setServerTransport(message.getServerTransport());
        reply.setBayeuxContext(message.getBayeuxContext());
        reply.setChannel(message.getChannel());
        String id = message.getId();
        if (id != null) {
            reply.setId(id);
        }
        Object subscriptions = message.get(Message.SUBSCRIPTION_FIELD);
        if (subscriptions != null) {
            reply.put(Message.SUBSCRIPTION_FIELD, subscriptions);
        }
        return reply;
    }

    private boolean validateSubscriptions(List<String> subscriptions) {
        if (_validation) {
            for (String subscription : subscriptions) {
                if (!validate(subscription)) {
                    return false;
                }
            }
        }
        return true;
    }

    @ManagedOperation(value = "Sweeps channels and sessions of this BayeuxServer", impact = "ACTION")
    public void sweep() {
        for (ServerChannelImpl channel : _channels.values()) {
            channel.sweep();
        }

        for (ServerTransport transport : _transports.values()) {
            if (transport instanceof AbstractServerTransport) {
                ((AbstractServerTransport)transport).sweep();
            }
        }

        long now = System.nanoTime();
        for (ServerSessionImpl session : _sessions.values()) {
            session.sweep(now);
        }
    }

    @ManagedAttribute("Reports additional details in the dump")
    public boolean isDetailedDump() {
        return _detailedDump;
    }

    public void setDetailedDump(boolean detailedDump) {
        _detailedDump = detailedDump;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        List<Object> children = new ArrayList<>();

        SecurityPolicy securityPolicy = getSecurityPolicy();
        if (securityPolicy != null) {
            children.add(securityPolicy);
        }

        List<?> transports = _allowedTransports;
        if (isDetailedDump()) {
            transports = transports.stream()
                    .map(t -> getTransport((String)t))
                    .collect(Collectors.toList());
        }
        children.add(new DumpableCollection("transports", transports));

        if (isDetailedDump()) {
            children.add(new DumpableCollection("channels", new TreeMap<>(_channels).values()));
        } else {
            children.add("channels size=" + _channels.size());
        }

        if (isDetailedDump()) {
            Map<Boolean, List<ServerSessionImpl>> sessions = _sessions.values().stream()
                    .collect(Collectors.groupingBy(ServerSessionImpl::isLocalSession));
            List<ServerSessionImpl> local = sessions.get(true);
            if (local != null) {
                children.add(new DumpableCollection("local sessions", local));
            }
            List<ServerSessionImpl> remote = sessions.get(false);
            if (remote != null) {
                children.add(new DumpableCollection("remote sessions", remote));
            }
        } else {
            children.add("sessions size=" + _sessions.size());
        }

        Dumpable.dumpObjects(out, indent, this, children.toArray());
    }

    private void handleMetaHandshake(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        BayeuxContext context = message.getBayeuxContext();
        if (context != null) {
            session.setUserAgent(context.getHeader("User-Agent"));
        }

        if (_policy != null) {
            _policy.canHandshake(this, session, message, Promise.from(can -> {
                if (can) {
                    handleMetaHandshake1(session, message, promise);
                } else {
                    ServerMessage.Mutable reply = message.getAssociated();
                    error(reply, "403::handshake_denied");
                    // The user's SecurityPolicy may have customized the response's advice
                    Map<String, Object> advice = reply.getAdvice(true);
                    if (!advice.containsKey(Message.RECONNECT_FIELD)) {
                        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                    }
                    promise.succeed(false);
                }
            }, promise::fail));
        } else {
            handleMetaHandshake1(session, message, promise);
        }
    }

    private void handleMetaHandshake1(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        if (session.handshake(message)) {
            addServerSession(session, message);

            reply.setSuccessful(true);
            reply.setClientId(session.getId());
            reply.put(Message.VERSION_FIELD, "1.0");
            reply.put(Message.MIN_VERSION_FIELD, "1.0");
            reply.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, getAllowedTransports());

            Map<String, Object> adviceOut = session.takeAdvice(message.getServerTransport());
            reply.put(Message.ADVICE_FIELD, adviceOut);

            promise.succeed(true);
        } else {
            error(reply, "403::handshake_failed");
            promise.succeed(false);
        }
    }

    private void handleMetaConnect(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        if (session.connected()) {
            reply.setSuccessful(true);

            Map<String, Object> adviceIn = message.getAdvice();
            if (adviceIn != null) {
                Number timeout = (Number)adviceIn.get("timeout");
                session.updateTransientTimeout(timeout == null ? -1L : timeout.longValue());
                Number interval = (Number)adviceIn.get("interval");
                session.updateTransientInterval(interval == null ? -1L : interval.longValue());
                // Force the server to send the advice, as the client may
                // have forgotten it (for example because of a reload)
                session.reAdvise();
            } else {
                session.updateTransientTimeout(-1);
                session.updateTransientInterval(-1);
            }
            Map<String, Object> adviceOut = session.takeAdvice(message.getServerTransport());
            if (adviceOut != null) {
                reply.put(Message.ADVICE_FIELD, adviceOut);
            }

            promise.succeed(true);
        } else {
            unknownSession(reply);
            promise.succeed(false);
        }
    }

    private void handleMetaSubscribe(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        Object subscriptionField = message.get(Message.SUBSCRIPTION_FIELD);
        if (subscriptionField == null) {
            error(reply, "403::subscription_missing");
            promise.succeed(false);
        } else {
            List<String> subscriptions = toChannelList(subscriptionField);
            if (subscriptions == null) {
                error(reply, "403::subscription_invalid");
                promise.succeed(false);
            } else {
                if (!validateSubscriptions(subscriptions)) {
                    error(reply, "403::subscription_invalid");
                    promise.succeed(false);
                } else {
                    AsyncFoldLeft.run(subscriptions, true, (result, subscription, loop) -> {
                        ServerChannelImpl channel = getServerChannel(subscription);
                        if (channel == null) {
                            isCreationAuthorized(session, message, subscription, Promise.from(creationResult -> {
                                if (creationResult instanceof Authorizer.Result.Denied) {
                                    String denyReason = ((Authorizer.Result.Denied)creationResult).getReason();
                                    error(reply, "403:" + denyReason + ":create_denied");
                                    loop.leave(false);
                                } else {
                                    handleMetaSubscribe1(session, message, (ServerChannelImpl)createChannelIfAbsent(subscription).getReference(), resolveLoop(loop));
                                }
                            }, promise::fail));
                        } else {
                            handleMetaSubscribe1(session, message, channel, resolveLoop(loop));
                        }
                    }, promise);
                }
            }
        }
    }

    private void handleMetaSubscribe1(ServerSessionImpl session, Mutable message, ServerChannelImpl channel, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        isSubscribeAuthorized(channel, session, message, Promise.from(subscribeResult -> {
            if (subscribeResult instanceof Authorizer.Result.Denied) {
                String denyReason = ((Authorizer.Result.Denied)subscribeResult).getReason();
                error(reply, "403:" + denyReason + ":subscribe_denied");
                promise.succeed(false);
            } else {
                if (channel.subscribe(session, message)) {
                    reply.setSuccessful(true);
                    promise.succeed(true);
                } else {
                    error(reply, "403::subscribe_failed");
                    promise.succeed(false);
                }
            }
        }, promise::fail));
    }

    private void handleMetaUnsubscribe(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        Object subscriptionField = message.get(Message.SUBSCRIPTION_FIELD);
        if (subscriptionField == null) {
            error(reply, "403::subscription_missing");
            promise.succeed(false);
        } else {
            List<String> subscriptions = toChannelList(subscriptionField);
            if (subscriptions == null) {
                error(reply, "403::subscription_invalid");
                promise.succeed(false);
            } else {
                if (!validateSubscriptions(subscriptions)) {
                    error(reply, "403::subscription_invalid");
                    promise.succeed(false);
                } else {
                    AsyncFoldLeft.run(subscriptions, true, (result, subscription, loop) -> {
                        ServerChannelImpl channel = getServerChannel(subscription);
                        if (channel == null) {
                            error(reply, "400::channel_missing");
                            loop.leave(false);
                        } else {
                            if (channel.unsubscribe(session, message)) {
                                reply.setSuccessful(true);
                                loop.proceed(true);
                            } else {
                                error(reply, "403::unsubscribe_failed");
                                loop.leave(false);
                            }
                        }
                    }, promise);
                }
            }
        }
    }

    private void handleMetaDisconnect(ServerSessionImpl session, Mutable message, Promise<Boolean> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        reply.setSuccessful(true);
        removeServerSession(session, message, false);
        // Wake up the possibly pending /meta/connect
        session.flush();
        promise.succeed(true);
    }

    private static List<String> toChannelList(Object channels) {
        if (channels instanceof String) {
            return Collections.singletonList((String)channels);
        }

        if (channels instanceof Object[]) {
            Object[] array = (Object[])channels;
            List<String> channelList = new ArrayList<>();
            for (Object o : array) {
                channelList.add(String.valueOf(o));
            }
            return channelList;
        }

        if (channels instanceof List) {
            List<?> list = (List<?>)channels;
            List<String> channelList = new ArrayList<>();
            for (Object o : list) {
                channelList.add(String.valueOf(o));
            }
            return channelList;
        }

        return null;
    }
}
