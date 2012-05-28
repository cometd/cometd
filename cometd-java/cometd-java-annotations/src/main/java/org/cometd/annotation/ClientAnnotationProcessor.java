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

package org.cometd.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;

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
 * @see ServerAnnotationProcessor
 */
public class ClientAnnotationProcessor extends AnnotationProcessor
{
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<Object, List<ListenerCallback>>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<Object, List<SubscriptionCallback>>();
    private final ClientSession clientSession;
    private final Object[] injectables;

    public ClientAnnotationProcessor(ClientSession clientSession)
    {
        this(clientSession, new Object[0]);
    }

    public ClientAnnotationProcessor(ClientSession clientSession, Object... injectables)
    {
        this.clientSession = clientSession;
        this.injectables = injectables;
    }
    /**
     * Processes dependencies annotated with {@link Session}, and callbacks
     * annotated with {@link Listener} and {@link Subscription}.
     * @param bean the annotated service instance
     * @return true if at least one dependency or callback has been processed, false otherwise
     */
    public boolean process(Object bean)
    {
        boolean result = processDependencies(bean);
        result |= processCallbacks(bean);
        result |= processPostConstruct(bean);
        return result;
    }

    /**
     * Processes lifecycle methods annotated with {@link PostConstruct}.
     * @param bean the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPostConstruct(Object bean)
    {
        return super.processPostConstruct(bean);
    }

    private boolean processCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = processListener(bean);
        result |= processSubscription(bean);
        return result;
    }

    /**
     * Performs the opposite processing done by {@link #process(Object)} on callbacks methods
     * annotated with {@link Listener} and {@link Subscription}, and on lifecycle methods annotated
     * with {@link PreDestroy}.
     * @param bean the annotated service instance
     * @return true if at least one deprocessing has been performed, false otherwise
     * @see #process(Object)
     */
    public boolean deprocess(Object bean)
    {
        boolean result = deprocessCallbacks(bean);
        result |= processPreDestroy(bean);
        return result;
    }

    /**
     * Deconfigures callbacks annotated with {@link Listener} and {@link Subscription}.
     * @param bean the annotated service instance
     * @return true if the at least one callback has been deconfigured
     */
    public boolean deprocessCallbacks(Object bean)
    {
        boolean result = deprocessListener(bean);
        result |= deprocessSubscription(bean);
        return result;
    }

    /**
     * Processes lifecycle methods annotated with {@link PreDestroy}.
     * @param bean the annotated service instance
     * @return true if at least one lifecycle method has been invoked, false otherwise
     */
    @Override
    public boolean processPreDestroy(Object bean)
    {
        return super.processPreDestroy(bean);
    }

    private boolean processDependencies(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = processInjectables(bean, Arrays.asList(injectables));
        result |= processSession(bean, clientSession);
        return result;
    }

    private boolean processSession(Object bean, ClientSession clientSession)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass())
        {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields)
            {
                if (field.getAnnotation(Session.class) != null)
                {
                    if (field.getType().isAssignableFrom(clientSession.getClass()))
                    {
                        setField(bean, field, clientSession);
                        result = true;
                        logger.debug("Injected {} to field {} on bean {}", new Object[]{clientSession, field, bean});
                    }
                }
            }

            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                if (method.getAnnotation(Session.class) != null)
                {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1)
                    {
                        if (parameterTypes[0].isAssignableFrom(clientSession.getClass()))
                        {
                            invokeMethod(bean, method, clientSession);
                            result = true;
                            logger.debug("Injected {} to method {} on bean {}", new Object[]{clientSession, method, bean});
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean processListener(Object bean)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass())
        {
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                Listener listener = method.getAnnotation(Listener.class);
                if (listener != null)
                {
                    String[] channels = listener.value();
                    for (String channel : channels)
                    {
                        ListenerCallback listenerCallback = new ListenerCallback(bean, method, channel);
                        clientSession.getChannel(channel).addListener(listenerCallback);

                        List<ListenerCallback> callbacks = listeners.get(bean);
                        if (callbacks == null)
                        {
                            callbacks = new CopyOnWriteArrayList<ListenerCallback>();
                            List<ListenerCallback> existing = listeners.putIfAbsent(bean, callbacks);
                            if (existing != null)
                                callbacks = existing;
                        }
                        callbacks.add(listenerCallback);
                        result = true;
                        logger.debug("Registered listener for channel {} to method {} on bean {}", new Object[]{channel, method, bean});
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessListener(Object bean)
    {
        boolean result = false;
        List<ListenerCallback> callbacks = listeners.get(bean);
        if (callbacks != null)
        {
            for (ListenerCallback callback : callbacks)
            {
                ClientSessionChannel channel = clientSession.getChannel(callback.channel);
                if (channel != null)
                {
                    channel.removeListener(callback);
                    result = true;
                }
            }
        }
        return result;
    }

    private boolean processSubscription(Object bean)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != Object.class; c = c.getSuperclass())
        {
            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                Subscription subscription = method.getAnnotation(Subscription.class);
                if (subscription != null)
                {
                    String[] channels = subscription.value();
                    for (String channel : channels)
                    {
                        SubscriptionCallback subscriptionCallback = new SubscriptionCallback(clientSession, bean, method, channel);
                        // We should delay the subscription if the client session did not complete the handshake
                        if (clientSession.isHandshook())
                            clientSession.getChannel(channel).subscribe(subscriptionCallback);
                        else
                            clientSession.getChannel(Channel.META_HANDSHAKE).addListener(subscriptionCallback);

                        List<SubscriptionCallback> callbacks = subscribers.get(bean);
                        if (callbacks == null)
                        {
                            callbacks = new CopyOnWriteArrayList<SubscriptionCallback>();
                            List<SubscriptionCallback> existing = subscribers.putIfAbsent(bean, callbacks);
                            if (existing != null)
                                callbacks = existing;
                        }
                        callbacks.add(subscriptionCallback);
                        result = true;
                        logger.debug("Registered subscriber for channel {} to method {} on bean {}", new Object[]{channel, method, bean});
                    }
                }
            }
        }
        return result;
    }

    private boolean deprocessSubscription(Object bean)
    {
        boolean result = false;
        List<SubscriptionCallback> callbacks = subscribers.get(bean);
        if (callbacks != null)
        {
            for (SubscriptionCallback callback : callbacks)
            {
                clientSession.getChannel(callback.channel).unsubscribe(callback);
                result = true;
            }
        }
        return result;
    }

    private static class ListenerCallback implements ClientSessionChannel.MessageListener
    {
        private static final Class<?>[] signature = new Class[]{Message.class};
        private final Object target;
        private final Method method;
        private final String channel;

        private ListenerCallback(Object target, Method method, String channel)
        {
            Class<?>[] parameters = method.getParameterTypes();
            if (!signaturesMatch(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature for method " + method);
            if (!ChannelId.isMeta(channel))
                throw new IllegalArgumentException("Annotation @Listener on method " + method + " must specify a meta channel");
            this.target = target;
            this.method = method;
            this.channel = channel;
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            boolean accessible = method.isAccessible();
            try
            {
                method.setAccessible(true);
                method.invoke(target, message);
            }
            catch (InvocationTargetException x)
            {
                throw new RuntimeException(x.getCause());
            }
            catch (IllegalAccessException x)
            {
                throw new RuntimeException(x);
            }
            finally
            {
                method.setAccessible(accessible);
            }
        }
    }

    private static class SubscriptionCallback implements ClientSessionChannel.MessageListener
    {
        private static final Class<?>[] signature = new Class[]{Message.class};
        private final ClientSession clientSession;
        private final Object target;
        private final Method method;
        private final String channel;

        public SubscriptionCallback(ClientSession clientSession, Object target, Method method, String channel)
        {
            Class<?>[] parameters = method.getParameterTypes();
            if (!signaturesMatch(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature for method " + method);
            if (ChannelId.isMeta(channel))
                throw new IllegalArgumentException("Annotation @Subscription on method " + method + " must specify a non meta channel");
            this.clientSession = clientSession;
            this.target = target;
            this.method = method;
            this.channel = channel;
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (Channel.META_HANDSHAKE.equals(channel.getId()))
            {
                if (message.isSuccessful())
                    subscribe();
            }
            else
            {
                forward(message);
            }
        }

        private void subscribe()
        {
            clientSession.getChannel(channel).subscribe(this);
            clientSession.getChannel(Channel.META_HANDSHAKE).removeListener(this);
        }

        private void forward(Message message)
        {
            boolean accessible = method.isAccessible();
            try
            {
                method.setAccessible(true);
                method.invoke(target, message);
            }
            catch (InvocationTargetException x)
            {
                throw new RuntimeException(x.getCause());
            }
            catch (IllegalAccessException x)
            {
                throw new RuntimeException(x);
            }
            finally
            {
                method.setAccessible(accessible);
            }
        }
    }
}
