/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
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
 * <pre>
 * &#64;Service
 * public class MyService
 * {
 *     &#64;Session
 *     private ServerSession session;
 *
 *     &#64;Configure("/foo")
 *     public void configureFoo(ConfigurableServerChannel channel)
 *     {
 *         channel.setPersistent(...);
 *         channel.addListener(...);
 *         channel.addAuthorizer(...);
 *     }
 *
 *     &#64;Listener("/foo")
 *     public void handleFooMessages(ServerSession remote, ServerMessage.Mutable message)
 *     {
 *         // Do something
 *     }
 * }
 * </pre>
 * <p>The processor is used in this way:</p>
 * <pre>
 * BayeuxServer bayeux = ...;
 * ServerAnnotationProcessor processor = ServerAnnotationProcessor.get(bayeux);
 * MyService s = new MyService();
 * processor.process(s);
 * </pre>
 *
 * @see ClientAnnotationProcessor
 */
public class ServerAnnotationProcessor extends AnnotationProcessor {
    private final ConcurrentMap<Object, LocalSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<RemoteCallCallback>> remoteCalls = new ConcurrentHashMap<>();
    private final BayeuxServer bayeuxServer;
    private final Object[] injectables;

    public ServerAnnotationProcessor(BayeuxServer bayeuxServer) {
        this(bayeuxServer, new Object[0]);
    }

    public ServerAnnotationProcessor(BayeuxServer bayeuxServer, Object... injectables) {
        this.bayeuxServer = bayeuxServer;
        this.injectables = injectables;
    }

    /**
     * Processes dependencies annotated with {@link Inject} and {@link Session},
     * configuration methods annotated with {@link Configure}, callback methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall},
     * and lifecycle methods annotated with {@link PostConstruct}.
     *
     * @param bean the annotated service instance
     * @return true if the bean contains at least one annotation that has been processed, false otherwise
     */
    public boolean process(Object bean) {
        boolean result = processDependencies(bean);
        result |= processConfigurations(bean);
        result |= processCallbacks(bean);
        result |= processPostConstruct(bean);
        return result;
    }

    /**
     * Processes the methods annotated with {@link Configure}.
     *
     * @param bean the annotated service instance
     * @return true if at least one annotated configure has been processed, false otherwise
     */
    public boolean processConfigurations(final Object bean) {
        if (bean == null) {
            return false;
        }

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        List<Method> methods = findAnnotatedMethods(bean, Configure.class);
        if (methods.isEmpty()) {
            return false;
        }

        for (final Method method : methods) {
            Configure configure = method.getAnnotation(Configure.class);
            String[] channels = configure.value();
            for (String channel : channels) {
                final Initializer init = new Initializer() {
                    @Override
                    public void configureChannel(ConfigurableServerChannel channel) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Configure channel {} with method {} on bean {}", channel, method, bean);
                        }
                        invokePrivate(bean, method, channel);
                    }
                };

                MarkedReference<ServerChannel> initializedChannel = bayeuxServer.createChannelIfAbsent(channel, init);

                if (!initializedChannel.isMarked()) {
                    if (configure.configureIfExists()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Configure again channel {} with method {} on bean {}", channel, method, bean);
                        }
                        init.configureChannel(initializedChannel.getReference());
                    } else if (configure.errorIfExists()) {
                        throw new IllegalStateException("Channel already configured: " + channel);
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Channel {} already initialized. Not called method {} on bean {}", channel, method, bean);
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
     * @param bean the annotated service instance
     * @return true if at least one annotated dependency has been processed, false otherwise
     */
    public boolean processDependencies(Object bean) {
        if (bean == null) {
            return false;
        }

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        List<Object> injectables = new ArrayList<>(Arrays.asList(this.injectables));
        injectables.add(0, bayeuxServer);
        boolean result = processInjectables(bean, injectables);
        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.value());
        result |= processSession(bean, session);
        return result;
    }

    /**
     * Processes lifecycle methods annotated with {@link PostConstruct}.
     *
     * @param bean the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPostConstruct(Object bean) {
        return super.processPostConstruct(bean);
    }

    /**
     * Processes the callbacks annotated with {@link Listener}, {@link Subscription}
     * and {@link RemoteCall}.
     *
     * @param bean the annotated service instance
     * @return true if at least one annotated callback has been processed, false otherwise
     */
    public boolean processCallbacks(Object bean) {
        if (bean == null) {
            return false;
        }

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        if (!Modifier.isPublic(klass.getModifiers())) {
            throw new IllegalArgumentException("Service class " + klass.getName() + " must be public");
        }

        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.value());
        boolean result = processListener(bean, session);
        result |= processSubscription(bean, session);
        result |= processRemoteCall(bean, session);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #process(Object)} on callbacks methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall}, and on
     * lifecycle methods annotated with {@link PreDestroy}.
     *
     * @param bean the annotated service instance
     * @return true if at least one deprocessing has been performed, false otherwise
     * @see #process(Object)
     */
    public boolean deprocess(Object bean) {
        boolean result = deprocessCallbacks(bean);
        result |= processPreDestroy(bean);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #processCallbacks(Object)} on callback methods
     * annotated with {@link Listener}, {@link Subscription} and {@link RemoteCall}.
     *
     * @param bean the annotated service instance
     * @return true if the at least one callback has been deprocessed
     */
    public boolean deprocessCallbacks(Object bean) {
        if (bean == null) {
            return false;
        }

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        boolean result = deprocessListener(bean);
        result |= deprocessSubscription(bean);
        result |= deprocessRemoteCall(bean);
        destroyLocalSession(bean);
        return result;
    }

    private void destroyLocalSession(Object bean) {
        LocalSession session = sessions.remove(bean);
        if (session != null) {
            session.disconnect();
        }
    }

    /**
     * Processes lifecycle methods annotated with {@link PreDestroy}.
     *
     * @param bean the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPreDestroy(Object bean) {
        return super.processPreDestroy(bean);
    }

    private LocalSession findOrCreateLocalSession(Object bean, String name) {
        LocalSession session = sessions.get(bean);
        if (session == null) {
            session = bayeuxServer.newLocalSession(name);
            LocalSession existing = sessions.putIfAbsent(bean, session);
            if (existing != null) {
                session = existing;
            } else {
                session.handshake();
            }
        }
        return session;
    }

    private boolean processSession(Object bean, LocalSession localSession) {
        ServerSession serverSession = localSession.getServerSession();

        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
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
                        setField(bean, field, value);
                        result = true;
                        if (logger.isDebugEnabled()) {
                            logger.debug("Injected {} to field {} on bean {}", value, field, bean);
                        }
                    }
                }
            }
        }

        List<Method> methods = findAnnotatedMethods(bean, Session.class);
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
                    invokePrivate(bean, method, value);
                    result = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Injected {} to method {} on bean {}", value, method, bean);
                    }
                }
            }
        }
        return result;
    }

    private boolean processListener(Object bean, LocalSession localSession) {
        checkMethodsPublic(bean, Listener.class);

        boolean result = false;
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            Listener listener = method.getAnnotation(Listener.class);
            if (listener != null) {
                List<String> paramNames = processParameters(method);
                checkSignaturesMatch(method, ListenerCallback.signature, paramNames);

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
                    ListenerCallback listenerCallback = new ListenerCallback(localSession, bean, method, paramNames, channelId, channel, listener.receiveOwnPublishes());
                    initializedChannel.getReference().addListener(listenerCallback);

                    List<ListenerCallback> callbacks = listeners.get(bean);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<ListenerCallback> existing = listeners.putIfAbsent(bean, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(listenerCallback);
                    result = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Registered listener for channel {} to method {} on bean {}", channel, method, bean);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessListener(Object bean) {
        boolean result = false;
        List<ListenerCallback> callbacks = listeners.remove(bean);
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

    private boolean processSubscription(Object bean, LocalSession localSession) {
        checkMethodsPublic(bean, Subscription.class);

        boolean result = false;
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }

            Subscription subscription = method.getAnnotation(Subscription.class);
            if (subscription != null) {
                List<String> paramNames = processParameters(method);
                checkSignaturesMatch(method, SubscriptionCallback.signature, paramNames);

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

                    SubscriptionCallback subscriptionCallback = new SubscriptionCallback(localSession, bean, method, paramNames, channelId, channel);
                    localSession.getChannel(channel).subscribe(subscriptionCallback);

                    List<SubscriptionCallback> callbacks = subscribers.get(bean);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<SubscriptionCallback> existing = subscribers.putIfAbsent(bean, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(subscriptionCallback);
                    result = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Registered subscriber for channel {} to method {} on bean {}", channel, method, bean);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessSubscription(Object bean) {
        boolean result = false;
        List<SubscriptionCallback> callbacks = subscribers.remove(bean);
        if (callbacks != null) {
            for (SubscriptionCallback callback : callbacks) {
                callback.localSession.getChannel(callback.subscription).unsubscribe(callback);
                result = true;
            }
        }
        return result;
    }

    private boolean processRemoteCall(Object bean, LocalSession localSession) {
        checkMethodsPublic(bean, RemoteCall.class);

        boolean result = false;
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            RemoteCall remoteCall = method.getAnnotation(RemoteCall.class);
            if (remoteCall != null) {
                List<String> paramNames = processParameters(method);
                checkSignaturesMatch(method, RemoteCallCallback.signature, paramNames);

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
                    RemoteCallCallback remoteCallCallback = new RemoteCallCallback(bayeuxServer, localSession, bean, method, paramNames, channelId, channel);
                    initializedChannel.getReference().addListener(remoteCallCallback);

                    List<RemoteCallCallback> callbacks = remoteCalls.get(bean);
                    if (callbacks == null) {
                        callbacks = new CopyOnWriteArrayList<>();
                        List<RemoteCallCallback> existing = remoteCalls.putIfAbsent(bean, callbacks);
                        if (existing != null) {
                            callbacks = existing;
                        }
                    }
                    callbacks.add(remoteCallCallback);
                    result = true;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Registered remote call for channel {} to method {} on bean {}", target, method, bean);
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessRemoteCall(Object bean) {
        boolean result = false;
        List<RemoteCallCallback> callbacks = remoteCalls.remove(bean);
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
            return !Boolean.FALSE.equals(callPublic(target, method, args));
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
            callPublic(target, method, args);
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
                return !Boolean.FALSE.equals(invokePublic(target, method, args));
            } catch (Throwable x) {
                Map<String, Object> failure = new HashMap<>();
                failure.put("class", x.getClass().getName());
                failure.put("message", x.getMessage());
                caller.failure(failure);
                Class<?> klass = target.getClass();
                Logger logger = LoggerFactory.getLogger(klass);
                logger.info("Exception while invoking " + klass + "#" + method.getName() + "()", x);
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
                message.setSuccessful(successful);
                message.setChannel(channel);
                message.setData(data);
                session.deliver(sender, message);
            }
            return completed;
        }
    }
}
