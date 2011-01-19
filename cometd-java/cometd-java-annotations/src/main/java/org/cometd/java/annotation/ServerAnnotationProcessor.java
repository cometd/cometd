package org.cometd.java.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel.Initializer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

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
 * @see ClientAnnotationProcessor
 */
public class ServerAnnotationProcessor extends AnnotationProcessor
{
    private final ConcurrentMap<Object, LocalSession> sessions = new ConcurrentHashMap<Object, LocalSession>();
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<Object, List<ListenerCallback>>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<Object, List<SubscriptionCallback>>();
    private final BayeuxServer bayeuxServer;

    public ServerAnnotationProcessor(BayeuxServer bayeuxServer)
    {
        this.bayeuxServer = bayeuxServer;
    }

    /**
     * Processes dependencies annotated with {@link Inject} and {@link Session}, lifecycle methods
     * annotated with {@link PostConstruct}, and callback methods annotated with {@link Listener}
     * and {@link Subscription}.
     * @param bean the annotated service instance
     * @return true if the bean contains at least one annotation that has been processed, false otherwise
     */
    public boolean process(Object bean)
    {
        boolean result = processDependencies(bean);
        result |= processConfigurations(bean);
        result |= processCallbacks(bean);
        result |= processPostConstruct(bean);
        return result;
    }

    /**
     * Processes the methods annotated with {@link Configure}
     * @param bean the annotated service instance
     * @return true if at least one annotated configure has been processed, false otherwise
     */
    public boolean processConfigurations(final Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
        {
            Method[] methods = c.getDeclaredMethods();
            for (final Method method : methods)
            {
                final Configure configure = method.getAnnotation(Configure.class);
                if (configure != null)
                {
                    result = true;
                    String[] channels = configure.value();
                    for (String channel : channels)
                    {
                        final Initializer init = new Initializer()
                        {
                            public void configureChannel(ConfigurableServerChannel channel)
                            {
                                boolean flip=false;
                                try
                                {
                                    logger.debug("Configure channel {} with method {} on bean {}", channel, method, bean);
                                    if (!method.isAccessible())
                                    {
                                        flip=true;
                                        method.setAccessible(true);
                                    }
                                    method.invoke(bean,channel);
                                }
                                catch(Exception e)
                                {
                                    logger.warn(e);
                                    throw new RuntimeException(e);
                                }
                                finally
                                {
                                    if (flip)
                                        method.setAccessible(false);
                                }
                            }
                        };

                        boolean initialized = bayeuxServer.createIfAbsent(channel,init);

                        if (initialized)
                        {
                            logger.debug("Channel {} already initialzed. Not called method {} on bean {}", channel, method, bean);
                        }
                        else
                        {
                            if (configure.configureIfExists())
                            {
                                logger.debug("Configure channel {} with method {} on bean {}", channel, method, bean);
                                init.configureChannel(bayeuxServer.getChannel(channel));
                            }
                            else if (configure.errorIfExists())
                                throw new IllegalStateException("Channel already configured: "+channel);
                        }
                    }
                }
            }
        }
        return result;
    }


    /**
     * Processes the dependencies annotated with {@link Inject} and {@link Session}.
     * @param bean the annotated service instance
     * @return true if at least one annotated dependency has been processed, false otherwise
     */
    public boolean processDependencies(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = processBayeux(bean);
        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.value());
        result |= processSession(bean, session);
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

    /**
     * Processes the callbacks annotated with {@link Listener} and {@link Subscription}.
     * @param bean the annotated service instance
     * @return true if at least one annotated callback has been processed, false otherwise
     */
    public boolean processCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.value());
        boolean result = processListener(bean, session);
        result |= processSubscription(bean, session);
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
     * Performs the opposite processing done by {@link #processCallbacks(Object)} on callback methods
     * annotated with {@link Listener} and {@link Subscription}.
     * @param bean the annotated service instance
     * @return true if the at least one callback has been deprocessed
     */
    public boolean deprocessCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

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

    private LocalSession findOrCreateLocalSession(Object bean, String name)
    {
        LocalSession session = sessions.get(bean);
        if (session == null)
        {
            session = bayeuxServer.newLocalSession(name);
            LocalSession existing = sessions.putIfAbsent(bean, session);
            if (existing != null)
                session = existing;
            else
                session.handshake();
        }
        return session;
    }

    private boolean processBayeux(Object bean)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
        {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields)
            {
                if (field.getAnnotation(Inject.class) != null)
                {
                    if (field.getType().isAssignableFrom(bayeuxServer.getClass()))
                    {
                        Object value = getField(bean, field);
                        if (value != null)
                        {
                            logger.debug("Avoid injection of field {} on bean {}, it's already injected with {}", field, bean, value);
                            continue;
                        }

                        setField(bean, field, bayeuxServer);
                        result = true;
                        logger.debug("Injected {} to field {} on bean {}", bayeuxServer, field, bean);
                    }
                }
            }

            Method[] methods = c.getDeclaredMethods();
            for (Method method : methods)
            {
                if (method.getAnnotation(Inject.class) != null)
                {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1)
                    {
                        if (parameterTypes[0].isAssignableFrom(bayeuxServer.getClass()))
                        {
                            Method getter = findGetterMethod(c, method);
                            if (getter != null)
                            {
                                Object value = invokeMethod(bean, getter);
                                if (value != null)
                                {
                                    logger.debug("Avoid injection of method {} on bean {}, it's already injected with {}", method, bean, value);
                                    continue;
                                }
                            }

                            invokeMethod(bean, method, bayeuxServer);
                            result = true;
                            logger.debug("Injected {} to method {} on bean {}", bayeuxServer, method, bean);
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean processSession(Object bean, LocalSession localSession)
    {
        ServerSession serverSession = localSession.getServerSession();

        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
        {
            Field[] fields = c.getDeclaredFields();
            for (Field field : fields)
            {
                if (field.getAnnotation(Session.class) != null)
                {
                    Object value = null;
                    if (field.getType().isAssignableFrom(localSession.getClass()))
                        value = localSession;
                    else if (field.getType().isAssignableFrom(serverSession.getClass()))
                        value = serverSession;

                    if (value != null)
                    {
                        setField(bean, field, value);
                        result = true;
                        logger.debug("Injected {} to field {} on bean {}", value, field, bean);
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
                        Object value = null;
                        if (parameterTypes[0].isAssignableFrom(localSession.getClass()))
                            value = localSession;
                        else if (parameterTypes[0].isAssignableFrom(serverSession.getClass()))
                            value = serverSession;

                        if (value != null)
                        {
                            invokeMethod(bean, method, value);
                            result = true;
                            logger.debug("Injected {} to method {} on bean {}", value, method, bean);
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean processListener(Object bean, LocalSession localSession)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
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
                        bayeuxServer.createIfAbsent(channel);
                        ListenerCallback listenerCallback = new ListenerCallback(localSession, bean, method, channel, listener.receiveOwnPublishes());
                        bayeuxServer.getChannel(channel).addListener(listenerCallback);

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
                        logger.debug("Registered listener for channel {} to method {} on bean {}", channel, method, bean);
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
                ServerChannel channel = bayeuxServer.getChannel(callback.channel);
                if (channel != null)
                {
                    channel.removeListener(callback);
                    result = true;
                }
            }
        }
        return result;
    }

    private boolean processSubscription(Object bean, LocalSession localSession)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
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
                        SubscriptionCallback subscriptionCallback = new SubscriptionCallback(localSession, bean, method, channel);
                        localSession.getChannel(channel).subscribe(subscriptionCallback);

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
                        logger.debug("Registered subscriber for channel {} to method {} on bean {}", channel, method, bean);
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
                callback.localSession.getChannel(callback.channel).unsubscribe(callback);
                result = true;
            }
        }
        return result;
    }

    private static class ListenerCallback implements ServerChannel.MessageListener
    {
        private static final Class<?>[] signature = new Class[]{ServerSession.class, ServerMessage.Mutable.class};
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final String channel;
        private final boolean receiveOwnPublishes;

        private ListenerCallback(LocalSession localSession, Object target, Method method, String channel, boolean receiveOwnPublishes)
        {
            Class<?>[] parameters = method.getParameterTypes();
            if (!signaturesMatch(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature for method " + method);
            this.localSession = localSession;
            this.target = target;
            this.method = method;
            this.channel = channel;
            this.receiveOwnPublishes = receiveOwnPublishes;
        }

        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
        {
            if (from == localSession.getServerSession() && !receiveOwnPublishes)
                return true;

            boolean accessible = method.isAccessible();
            try
            {
                method.setAccessible(true);
                Object result = method.invoke(target, from, message);
                return result != Boolean.FALSE;
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
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final String channel;

        public SubscriptionCallback(LocalSession localSession, Object target, Method method, String channel)
        {
            Class<?>[] parameters = method.getParameterTypes();
            if (!signaturesMatch(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature for method " + method);
            this.localSession = localSession;
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
}
