/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.annotation.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.cometd.annotation.AnnotationProcessor;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.Subscription;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Processes annotations in client-side service objects.</p>
 * <p>Service objects must be annotated with {@link Service} at class level to be processed by this processor,
 * for example:</p>
 * <pre>
 * &#64;Service
 * public class MyService
 * {
 *     &#64;Listener(Channel.META_CONNECT)
 *     public void metaConnect(Message message)
 *     {
 *         // Do something
 *     }
 * }
 * </pre>
 * <p>The processor is used in this way:</p>
 * <pre>
 * ClientSession bayeux = ...;
 * ClientAnnotationProcessor processor = ClientAnnotationProcessor.get(bayeux);
 * MyService s = new MyService();
 * processor.process(s);
 * </pre>
 */
public class ClientAnnotationProcessor extends AnnotationProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientAnnotationProcessor.class);

    private final ConcurrentMap<Object, ClientSessionChannel.MessageListener> handshakeListeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<>();
    private final ClientSession clientSession;
    private final Object[] injectables;

    public ClientAnnotationProcessor(ClientSession clientSession) {
        this(clientSession, new Object[0]);
    }

    public ClientAnnotationProcessor(ClientSession clientSession, Object... injectables) {
        this.clientSession = clientSession;
        this.injectables = injectables;
    }

    /**
     * Processes dependencies annotated with {@link Session}, callbacks
     * annotated with {@link Listener} and {@link Subscription} and lifecycle
     * methods annotated with {@link PostConstruct}.
     *
     * @param bean the annotated service instance
     * @return true if at least one dependency or callback has been processed, false otherwise
     */
    public boolean process(Object bean) {
        processMetaHandshakeListener(bean);
        boolean result = processDependencies(bean);
        result |= processCallbacks(bean);
        result |= processPostConstruct(bean);
        return result;
    }

    private void processMetaHandshakeListener(Object bean) {
        if (bean != null) {
            MetaHandshakeListener listener = new MetaHandshakeListener(bean);
            ClientSessionChannel.MessageListener existing = handshakeListeners.putIfAbsent(bean, listener);
            if (existing == null) {
                clientSession.getChannel(Channel.META_HANDSHAKE).addListener(listener);
            }
        }
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

    private boolean processCallbacks(Object bean) {
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

        boolean result = processListener(bean);
        result |= processSubscription(bean);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #process(Object)} on callbacks methods
     * annotated with {@link Listener} and {@link Subscription}, and on lifecycle methods annotated
     * with {@link PreDestroy}.
     *
     * @param bean the annotated service instance
     * @return true if at least one deprocessing has been performed, false otherwise
     * @see #process(Object)
     */
    public boolean deprocess(Object bean) {
        deprocessMetaHandshakeListener(bean);
        boolean result = deprocessCallbacks(bean);
        result |= processPreDestroy(bean);
        return result;
    }

    private void deprocessMetaHandshakeListener(Object bean) {
        ClientSessionChannel.MessageListener listener = handshakeListeners.remove(bean);
        if (listener != null) {
            clientSession.getChannel(Channel.META_HANDSHAKE).removeListener(listener);
        }
    }

    /**
     * Performs the opposite processing done by {@link #processCallbacks(Object)}
     * on callback methods annotated with {@link Listener} and {@link Subscription}.
     *
     * @param bean the annotated service instance
     * @return true if the at least one callback has been deprocessed
     */
    public boolean deprocessCallbacks(Object bean) {
        boolean result = deprocessListener(bean);
        result |= deprocessSubscription(bean);
        return result;
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

    private boolean processDependencies(Object bean) {
        if (bean == null) {
            return false;
        }

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null) {
            return false;
        }

        boolean result = processInjectables(bean, List.of(injectables));
        result |= processSession(bean, clientSession);
        return result;
    }

    private boolean processSession(Object bean, ClientSession clientSession) {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass()) {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields) {
                if (field.getAnnotation(Session.class) != null) {
                    if (field.getType().isAssignableFrom(clientSession.getClass())) {
                        setField(bean, field, clientSession);
                        result = true;
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Injected {} to field {} on bean {}", clientSession, field, bean);
                        }
                    }
                }
            }
        }

        List<Method> methods = findAnnotatedMethods(bean, Session.class);
        for (Method method : methods) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 1) {
                if (parameterTypes[0].isAssignableFrom(clientSession.getClass())) {
                    invokePrivate(bean, method, clientSession);
                    result = true;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Injected {} to method {} on bean {}", clientSession, method, bean);
                    }
                }
            }
        }
        return result;
    }

    private boolean processListener(Object bean) {
        AnnotationProcessor.checkMethodsPublic(bean, Listener.class);

        boolean result = false;
        Method[] methods = bean.getClass().getMethods();
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
                    if (!ChannelId.isMeta(channel)) {
                        throw new IllegalArgumentException("Annotation @" + Listener.class.getSimpleName() +
                                " on method " + method.getDeclaringClass().getName() + "." + method.getName() +
                                "(...) must specify a meta channel");
                    }

                    ChannelId channelId = new ChannelId(channel);
                    if (channelId.isTemplate()) {
                        channel = channelId.getWildIds().get(0);
                    }

                    ListenerCallback listenerCallback = new ListenerCallback(bean, method, paramNames, channelId, channel);
                    clientSession.getChannel(channel).addListener(listenerCallback);

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
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registered listener for channel {} to method {} on bean {}", channel, method, bean);
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
                ClientSessionChannel channel = clientSession.getChannel(callback.subscription);
                if (channel != null) {
                    channel.removeListener(callback);
                    result = true;
                }
            }
        }
        return result;
    }

    private boolean processSubscription(Object bean) {
        AnnotationProcessor.checkMethodsPublic(bean, Subscription.class);

        boolean result = false;
        Method[] methods = bean.getClass().getMethods();
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
                        channel = channelId.getWildIds().get(0);
                    }

                    SubscriptionCallback subscriptionCallback = new SubscriptionCallback(clientSession, bean, method, paramNames, channelId, channel);
                    // We should delay the subscription if the client session did not complete the handshake
                    if (clientSession.isHandshook()) {
                        clientSession.getChannel(channel).subscribe(subscriptionCallback);
                    }

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
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registered subscriber for channel {} to method {} on bean {}", channel, method, bean);
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
                clientSession.getChannel(callback.subscription).unsubscribe(callback);
                result = true;
            }
        }
        return result;
    }

    private static class ListenerCallback implements ClientSessionChannel.MessageListener {
        private static final Class<?>[] signature = new Class<?>[]{Message.class};
        private final Object target;
        private final Method method;
        private final List<String> paramNames;
        private final ChannelId channelId;
        private final String subscription;

        private ListenerCallback(Object target, Method method, List<String> paramNames, ChannelId channelId, String subscription) {
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

    private static class SubscriptionCallback implements ClientSessionChannel.MessageListener {
        private static final Class<?>[] signature = new Class<?>[]{Message.class};
        private final ClientSession clientSession;
        private final Object target;
        private final Method method;
        private final List<String> paramNames;
        private final ChannelId channelId;
        private final String subscription;

        public SubscriptionCallback(ClientSession clientSession, Object target, Method method, List<String> paramNames, ChannelId channelId, String subscription) {
            this.clientSession = clientSession;
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

        private void subscribe() {
            clientSession.getChannel(subscription).subscribe(this);
        }
    }

    private class MetaHandshakeListener implements ClientSessionChannel.MessageListener {
        private final Object bean;

        public MetaHandshakeListener(Object bean) {
            this.bean = bean;
        }

        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (message.isSuccessful()) {
                List<SubscriptionCallback> subscriptions = subscribers.get(bean);
                if (subscriptions != null) {
                    clientSession.batch(() -> {
                        for (SubscriptionCallback subscription : subscriptions) {
                            subscription.subscribe();
                        }
                    });
                }
            }
        }
    }
}
