/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.annotation.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.cometd.annotation.AnnotationProcessor;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel.Initializer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Processes annotations in server-side service objects.</p>
 * <p>Service objects must be annotated with {@link Service} at class level to be processed by this processor,
 * for example:</p>
 * <pre>{@code
 * @Service
 * public class MyService {
 *     @Session
 *     private ServerSession session;
 *
 *     @Configure("/foo")
 *     public void configureFoo(ConfigurableServerChannel channel) {
 *         channel.setPersistent(...);
 *         channel.addListener(...);
 *         channel.addAuthorizer(...);
 *     }
 *
 *     @Listener("/foo")
 *     public void handleFooMessages(ServerSession remote, ServerMessage.Mutable message) {
 *         // Do something
 *     }
 * }
 * }</pre>
 * <p>The processor is used in this way:</p>
 * <pre>{@code
 * BayeuxServer bayeux = ...;
 * ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
 * MyService s = new MyService();
 * processor.process(s);
 * }</pre>
 */
public class ServerAnnotationProcessor extends AnnotationProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerAnnotationProcessor.class);

    private final ConcurrentMap<Object, LocalSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<RemoteCallCallback>> remoteCalls = new ConcurrentHashMap<>();
    private final BayeuxServer bayeuxServer;
    private final List<Object> injectables;

    public ServerAnnotationProcessor(BayeuxServer bayeuxServer) {
        this(bayeuxServer, new Object[0]);
    }

    public ServerAnnotationProcessor(BayeuxServer bayeuxServer, Object... injectables) {
        this.bayeuxServer = bayeuxServer;
        this.injectables = List.of(injectables);
    }

    /**
     * Processes dependencies annotated with {@link Inject} and {@link Session},
     * configuration methods annotated with {@link Configure}, callback methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall},
     * and lifecycle methods annotated with {@link PostConstruct}.
     *
     * @param service the annotated service instance
     * @param injectables additional objects that may be injected into the service instance
     * @return true if the service contains at least one annotation that has been processed, false otherwise
     */
    public boolean process(Object service, Object... injectables) {
        boolean result = processDependencies(service, injectables);
        result |= processConfigurations(service);
        result |= processCallbacks(service);
        result |= processPostConstruct(service);
        return result;
    }

    /**
     * Processes the methods annotated with {@link Configure}.
     *
     * @param service the annotated service instance
     * @return true if at least one annotated configure has been processed, false otherwise
     */
    public boolean processConfigurations(Object service) {
        if (service == null) {
            return false;
        }

        Class<?> klass = service.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        List<Method> methods = findAnnotatedMethods(service, Configure.class);
        if (methods.isEmpty()) {
            return false;
        }

        for (Method method : methods) {
            Configure configure = method.getAnnotation(Configure.class);
            String[] channels = configure.value();
            for (String channelName : channels) {
                Initializer init = channel -> {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Configure channel {} with method {} on service {}", channel, method, service);
                    }
                    invokePrivate(service, method, channel);
                };

                MarkedReference<ServerChannel> initializedChannel = bayeuxServer.createChannelIfAbsent(channelName, init);

                if (!initializedChannel.isMarked()) {
                    if (configure.configureIfExists()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Configure again channel {} with method {} on service {}", channelName, method, service);
                        }
                        init.configureChannel(initializedChannel.getReference());
                    } else if (configure.errorIfExists()) {
                        throw new IllegalStateException("Channel already configured: " + channelName);
                    } else {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Channel {} already initialized. Not called method {} on service {}", channelName, method, service);
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Processes the dependencies annotated with {@link Inject} and {@link Session}.
     *
     * @param service the annotated service instance
     * @param extraInjectables additional objects that may be injected into the service instance
     * @return true if at least one annotated dependency has been processed, false otherwise
     */
    public boolean processDependencies(Object service, Object... extraInjectables) {
        if (service == null) {
            return false;
        }

        Class<?> klass = service.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        List<Object> allInjectables = new ArrayList<>();
        allInjectables.add(bayeuxServer);
        allInjectables.addAll(injectables);
        allInjectables.addAll(List.of(extraInjectables));
        boolean result = processInjectables(service, allInjectables);
        LocalSession session = findOrCreateLocalSession(service, serviceAnnotation.value());
        result |= processSession(service, session);
        return result;
    }

    /**
     * Processes lifecycle methods annotated with {@link PostConstruct}.
     *
     * @param service the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPostConstruct(Object service) {
        return super.processPostConstruct(service);
    }

    /**
     * Processes the callbacks annotated with {@link Listener}, {@link Subscription}
     * and {@link RemoteCall}.
     *
     * @param service the annotated service instance
     * @return true if at least one annotated callback has been processed, false otherwise
     */
    public boolean processCallbacks(Object service) {
        if (service == null) {
            return false;
        }

        Class<?> klass = service.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        if (!Modifier.isPublic(klass.getModifiers())) {
            throw new IllegalArgumentException("Service class " + klass.getName() + " must be public");
        }

        LocalSession session = findOrCreateLocalSession(service, serviceAnnotation.value());
        boolean result = processListener(service, session);
        result |= processSubscription(service, session);
        result |= processRemoteCall(service, session);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #process(Object, Object...)} on callbacks methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall}, and on
     * lifecycle methods annotated with {@link PreDestroy}.
     *
     * @param service the annotated service instance
     * @return true if at least one deprocessing has been performed, false otherwise
     * @see #process(Object, Object...)
     */
    public boolean deprocess(Object service) {
        boolean result = deprocessCallbacks(service);
        result |= processPreDestroy(service);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #processCallbacks(Object)} on callback methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall}.
     *
     * @param service the annotated service instance
     * @return true if the at least one callback has been deprocessed
     */
    public boolean deprocessCallbacks(Object service) {
        if (service == null) {
            return false;
        }

        Class<?> klass = service.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        boolean result = deprocessListener(service);
        result |= deprocessSubscription(service);
        result |= deprocessRemoteCall(service);
        destroyLocalSession(service);
        return result;
    }

    private void destroyLocalSession(Object service) {
        LocalSession session = sessions.remove(service);
        if (session != null) {
            session.disconnect();
        }
    }

    /**
     * Processes lifecycle methods annotated with {@link PreDestroy}.
     *
     * @param service the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPreDestroy(Object service) {
        return super.processPreDestroy(service);
    }

    private LocalSession findOrCreateLocalSession(Object service, String name) {
        LocalSession session = sessions.get(service);
        if (session == null) {
            session = bayeuxServer.newLocalSession(name);
            LocalSession existing = sessions.putIfAbsent(service, session);
            if (existing != null) {
                session = existing;
            } else {
                session.handshake();
            }
        }
        return session;
    }

    private boolean processSession(Object service, LocalSession localSession) {
        ServerSession serverSession = localSession.getServerSession();

        boolean result = false;
        for (Class<?> c = service.getClass(); c != Object.class; c = c.getSuperclass()) {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields) {
                if (field.getAnnotation(Session.class) != null) {
                    Object value = null;
                    if (field.getType().isAssignableFrom(localSession.getClass())) {
                        value = localSession;
                    } else if (field.getType().isAssignableFrom(serverSession.getClass())) {
                        value = serverSession;
                    }

                    if (value != null) {
                        setField(service, field, value);
                        result = true;
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Injected {} to field {} on service {}", value, field, service);
                        }
                    }
                }
            }
        }

        List<Method> methods = findAnnotatedMethods(service, Session.class);
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                Object value = null;
                if (parameterTypes[0].isAssignableFrom(localSession.getClass())) {
                    value = localSession;
                } else if (parameterTypes[0].isAssignableFrom(serverSession.getClass())) {
                    value = serverSession;
                }

                if (value != null) {
                    invokePrivate(service, method, value);
                    result = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Injected {} to method {} on service {}", value, method, service);
                    }
                }
            }
        }
        return result;
    }

    private boolean processListener(Object service, LocalSession localSession) {
        AnnotationProcessor.checkMethodsPublic(service, Listener.class);

        boolean result = false;
        Method[] methods = service.getClass().getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            Listener listener = method.getAnnotation(Listener.class);
            if (listener != null) {
                List<String> paramNames = processParameters(method);
                AnnotationProcessor.checkSignaturesMatch(method, ListenerCallback.signature, paramNames);

                String[] channels = listener.value();
                for (String channel : channels) {
                    ChannelId channelId = new ChannelId(channel);
                    if (channelId.isTemplate()) {
                        List<String> parameters = channelId.getParameters();
                        if (parameters.size() != paramNames.size()) {
                            throw new IllegalArgumentException("Wrong number of template parameters in annotation @" +
                                    Listener.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        if (!parameters.equals(paramNames)) {
                            throw new IllegalArgumentException("Wrong parameter names in annotation @" +
                                    Listener.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        channel = channelId.getRegularPart() + "/" + (parameters.size() < 2 ? ChannelId.WILD : ChannelId.DEEPWILD);
                    }

                    MarkedReference<ServerChannel> initializedChannel = bayeuxServer.createChannelIfAbsent(channel);
                    ListenerCallback listenerCallback = new ListenerCallback(localSession, service, method, paramNames, channelId, channel, listener.receiveOwnPublishes());
                    initializedChannel.getReference().addListener(listenerCallback);

                    List<ListenerCallback> callbacks = listeners.get(service);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<ListenerCallback> existing = listeners.putIfAbsent(service, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(listenerCallback);
                    result = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registered listener for channel {} to method {} on service {}", channel, method, service);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessListener(Object service) {
        boolean result = false;
        List<ListenerCallback> callbacks = listeners.remove(service);
        if (callbacks != null) {
            for (ListenerCallback callback : callbacks) {
                ServerChannel channel = bayeuxServer.getChannel(callback.subscription);
                if (channel != null) {
                    channel.removeListener(callback);
                    result = true;
                }
            }
        }
        return result;
    }

    private boolean processSubscription(Object service, LocalSession localSession) {
        AnnotationProcessor.checkMethodsPublic(service, Subscription.class);

        boolean result = false;
        Method[] methods = service.getClass().getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            Subscription subscription = method.getAnnotation(Subscription.class);
            if (subscription != null) {
                List<String> paramNames = processParameters(method);
                AnnotationProcessor.checkSignaturesMatch(method, SubscriptionCallback.signature, paramNames);

                String[] channels = subscription.value();
                for (String channel : channels) {
                    if (ChannelId.isMeta(channel)) {
                        throw new IllegalArgumentException("Annotation @" + Subscription.class.getSimpleName() +
                                " on method " + method.getDeclaringClass().getName() + "." + method.getName() +
                                "(...) must specify a non meta channel");
                    }

                    ChannelId channelId = new ChannelId(channel);
                    if (channelId.isTemplate()) {
                        List<String> parameters = channelId.getParameters();
                        if (parameters.size() != paramNames.size()) {
                            throw new IllegalArgumentException("Wrong number of template parameters in annotation @" +
                                    Subscription.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        if (!parameters.equals(paramNames)) {
                            throw new IllegalArgumentException("Wrong parameter names in annotation @" +
                                    Subscription.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        channel = channelId.getRegularPart() + "/" + (parameters.size() < 2 ? ChannelId.WILD : ChannelId.DEEPWILD);
                    }

                    SubscriptionCallback subscriptionCallback = new SubscriptionCallback(localSession, service, method, paramNames, channelId, channel);
                    localSession.getChannel(channel).subscribe(subscriptionCallback);

                    List<SubscriptionCallback> callbacks = subscribers.get(service);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<SubscriptionCallback> existing = subscribers.putIfAbsent(service, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(subscriptionCallback);
                    result = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registered subscriber for channel {} to method {} on service {}", channel, method, service);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessSubscription(Object service) {
        boolean result = false;
        List<SubscriptionCallback> callbacks = subscribers.remove(service);
        if (callbacks != null) {
            for (SubscriptionCallback callback : callbacks) {
                callback.localSession.getChannel(callback.subscription).unsubscribe(callback);
                result = true;
            }
        }
        return result;
    }

    private boolean processRemoteCall(Object service, LocalSession localSession) {
        AnnotationProcessor.checkMethodsPublic(service, RemoteCall.class);

        boolean result = false;
        Method[] methods = service.getClass().getMethods();
        for (Method method : methods) {
            RemoteCall remoteCall = method.getAnnotation(RemoteCall.class);
            if (remoteCall != null) {
                List<String> paramNames = processParameters(method);
                AnnotationProcessor.checkSignaturesMatch(method, RemoteCallCallback.signature, paramNames);

                String[] targets = remoteCall.value();
                for (String target : targets) {
                    if (!target.startsWith("/")) {
                        target = "/" + target;
                    }
                    String channel = Channel.SERVICE + target;

                    ChannelId channelId = new ChannelId(channel);
                    if (channelId.isWild()) {
                        throw new IllegalArgumentException("Annotation @" + RemoteCall.class.getSimpleName() +
                                " on method " + method.getDeclaringClass().getName() + "." + method.getName() +
                                "(...) cannot specify wild channels.");
                    }

                    if (channelId.isTemplate()) {
                        List<String> parameters = channelId.getParameters();
                        if (parameters.size() != paramNames.size()) {
                            throw new IllegalArgumentException("Wrong number of template parameters in annotation @" +
                                    RemoteCall.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        if (!parameters.equals(paramNames)) {
                            throw new IllegalArgumentException("Wrong parameter names in annotation @" +
                                    RemoteCall.class.getSimpleName() + " on method " +
                                    method.getDeclaringClass().getName() + "." + method.getName() + "(...)");
                        }
                        channel = channelId.getRegularPart() + "/" + (parameters.size() < 2 ? ChannelId.WILD : ChannelId.DEEPWILD);
                    }

                    MarkedReference<ServerChannel> initializedChannel = bayeuxServer.createChannelIfAbsent(channel);
                    RemoteCallCallback remoteCallCallback = new RemoteCallCallback(bayeuxServer, localSession, service, method, paramNames, channelId, channel);
                    initializedChannel.getReference().addListener(remoteCallCallback);

                    List<RemoteCallCallback> callbacks = remoteCalls.get(service);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<RemoteCallCallback> existing = remoteCalls.putIfAbsent(service, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(remoteCallCallback);
                    result = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registered remote call for channel {} to method {} on service {}", target, method, service);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessRemoteCall(Object service) {
        boolean result = false;
        List<RemoteCallCallback> callbacks = remoteCalls.remove(service);
        if (callbacks != null) {
            for (RemoteCallCallback callback : callbacks) {
                ServerChannel channel = bayeuxServer.getChannel(callback.subscription);
                if (channel != null) {
                    channel.removeListener(callback);
                    result = true;
                }
            }
        }
        return result;
    }

    private static class ListenerCallback implements ServerChannel.MessageListener {
        private static final Class<?>[] signature = new Class<?>[]{ServerSession.class, ServerMessage.Mutable.class};
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final ChannelId channelId;
        private final String subscription;
        private final boolean receiveOwnPublishes;
        private final List<String> paramNames;

        private ListenerCallback(LocalSession localSession, Object target, Method method, List<String> paramNames, ChannelId channelId, String subscription, boolean receiveOwnPublishes) {
            this.localSession = localSession;
            this.target = target;
            this.method = method;
            this.paramNames = paramNames;
            this.channelId = channelId;
            this.subscription = subscription;
            this.receiveOwnPublishes = receiveOwnPublishes;
        }

        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            if (from == localSession.getServerSession() && !receiveOwnPublishes) {
                return true;
            }

            Map<String, String> matches = channelId.bind(channel.getChannelId());
            if (!paramNames.isEmpty() && !matches.keySet().containsAll(paramNames)) {
                return true;
            }

            Object[] args = new Object[2 + paramNames.size()];
            args[0] = from;
            args[1] = message;
            for (int i = 0; i < paramNames.size(); ++i) {
                args[2 + i] = matches.get(paramNames.get(i));
            }
            return !Boolean.FALSE.equals(AnnotationProcessor.callPublic(target, method, args));
        }
    }

    private static class SubscriptionCallback implements ClientSessionChannel.MessageListener {
        private static final Class<?>[] signature = new Class<?>[]{Message.class};
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final List<String> paramNames;
        private final ChannelId channelId;
        private final String subscription;

        public SubscriptionCallback(LocalSession localSession, Object target, Method method, List<String> paramNames, ChannelId channelId, String subscription) {
            this.localSession = localSession;
            this.target = target;
            this.method = method;
            this.paramNames = paramNames;
            this.channelId = channelId;
            this.subscription = subscription;
        }

        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            Map<String, String> matches = channelId.bind(message.getChannelId());
            if (!paramNames.isEmpty() && !matches.keySet().containsAll(paramNames)) {
                return;
            }

            Object[] args = new Object[1 + paramNames.size()];
            args[0] = message;
            for (int i = 0; i < paramNames.size(); ++i) {
                args[1 + i] = matches.get(paramNames.get(i));
            }
            AnnotationProcessor.callPublic(target, method, args);
        }
    }

    private static class RemoteCallCallback implements ServerChannel.MessageListener {
        private static final Class<?>[] signature = new Class<?>[]{RemoteCall.Caller.class, null};
        private final BayeuxServer bayeuxServer;
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final List<String> paramNames;
        private final ChannelId channelId;
        private final String subscription;

        private RemoteCallCallback(BayeuxServer bayeuxServer, LocalSession localSession, Object target, Method method, List<String> paramNames, ChannelId channelId, String subscription) {
            this.bayeuxServer = bayeuxServer;
            this.localSession = localSession;
            this.target = target;
            this.method = method;
            this.paramNames = paramNames;
            this.channelId = channelId;
            this.subscription = subscription;
        }

        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            // Protect against (wrong) publishes on the remote call channel.
            if (from == localSession.getServerSession()) {
                return true;
            }

            Map<String, String> matches = channelId.bind(channel.getChannelId());
            if (!paramNames.isEmpty() && !matches.keySet().containsAll(paramNames)) {
                return true;
            }

            Object[] args = new Object[2 + paramNames.size()];
            RemoteCall.Caller caller = new CallerImpl(bayeuxServer, localSession, from, message.getId(), message.getChannel());
            args[0] = caller;
            args[1] = message.getData();
            for (int i = 0; i < paramNames.size(); ++i) {
                args[2 + i] = matches.get(paramNames.get(i));
            }
            try {
                return !Boolean.FALSE.equals(AnnotationProcessor.invokePublic(target, method, args));
            } catch (Throwable x) {
                Map<String, Object> failure = new HashMap<>();
                failure.put("class", x.getClass().getName());
                failure.put("message", x.getMessage());
                caller.failure(failure);
                Class<?> klass = target.getClass();
                Logger logger = LoggerFactory.getLogger(klass);
                logger.info("Exception while invoking {}#{}()", klass, method.getName(), x);
                return true;
            }
        }
    }

    private static class CallerImpl implements RemoteCall.Caller {
        private final AtomicBoolean complete = new AtomicBoolean();
        private final BayeuxServer bayeux;
        private final LocalSession sender;
        private final ServerSession session;
        private final String messageId;
        private final String channel;

        private CallerImpl(BayeuxServer bayeux, LocalSession sender, ServerSession session, String messageId, String channel) {
            this.bayeux = bayeux;
            this.sender = sender;
            this.session = session;
            this.messageId = messageId;
            this.channel = channel;
        }

        @Override
        public ServerSession getServerSession() {
            return session;
        }

        @Override
        public boolean result(Object result) {
            return deliver(result, true);
        }

        @Override
        public boolean failure(Object failure) {
            return deliver(failure, false);
        }

        private boolean deliver(Object data, boolean successful) {
            boolean completed = complete.compareAndSet(false, true);
            if (completed) {
                ServerMessage.Mutable message = bayeux.newMessage();
                message.setId(messageId);
                // The "successful" field lets the client know whether
                // the reply is a result or a failure, as the "data" field
                // may be of the same type for both result and failure.
                message.setSuccessful(successful);
                message.setChannel(channel);
                message.setData(data);
                session.deliver(sender, message, Promise.noop());
            }
            return completed;
        }
    }
}
