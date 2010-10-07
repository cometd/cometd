package org.cometd.java.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

public class ServerAnnotationProcessor extends AnnotationProcessor
{
    private static final ConcurrentMap<BayeuxServer, ServerAnnotationProcessor> instances = new ConcurrentHashMap<BayeuxServer, ServerAnnotationProcessor>();

    public static ServerAnnotationProcessor get(BayeuxServer bayeuxServer)
    {
        ServerAnnotationProcessor result = instances.get(bayeuxServer);
        if (result == null)
        {
            result = new ServerAnnotationProcessor(bayeuxServer);
            ServerAnnotationProcessor existing = instances.putIfAbsent(bayeuxServer, result);
            if (existing != null)
                result = existing;
        }
        return result;
    }

    private final ConcurrentMap<Object, LocalSession> sessions = new ConcurrentHashMap<Object, LocalSession>();
    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<Object, List<ListenerCallback>>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<Object, List<SubscriptionCallback>>();
    private final BayeuxServer bayeuxServer;

    private ServerAnnotationProcessor(BayeuxServer bayeuxServer)
    {
        this.bayeuxServer = bayeuxServer;
    }

    public void close()
    {
        instances.remove(bayeuxServer);
    }

    public boolean configure(Object bean)
    {
        boolean result = configureDependencies(bean);
        result |= configureCallbacks(bean);
        return result;
    }

    public boolean configureDependencies(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = configureBayeux(bean);
        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.name());
        result |= configureSession(bean, session);
        return result;
    }

    public boolean configureCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        LocalSession session = findOrCreateLocalSession(bean, serviceAnnotation.name());
        boolean result = configureListener(bean, session);
        result |= configureSubscription(bean, session);
        return result;
    }

    public boolean deconfigureCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = deconfigureListener(bean);
        result |= deconfigureSubscription(bean);
        return result;
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

    private boolean configureBayeux(Object bean)
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
                            logger.info("Avoid injection of field {} on bean {}, it's already injected with {}", field, bean, value);
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
                                    logger.info("Avoid injection of method {} on bean {}, it's already injected with {}", method, bean, value);
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

    private boolean configureSession(Object bean, LocalSession localSession)
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

    private boolean configureListener(Object bean, LocalSession localSession)
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

    private boolean deconfigureListener(Object bean)
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

    private boolean configureSubscription(Object bean, LocalSession localSession)
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

    private boolean deconfigureSubscription(Object bean)
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
        private static final Class<?>[] signature = new Class[]{ServerSession.class, ServerMessage.class};
        private final LocalSession localSession;
        private final Object target;
        private final Method method;
        private final String channel;
        private final boolean receiveOwnPublishes;

        private ListenerCallback(LocalSession localSession, Object target, Method method, String channel, boolean receiveOwnPublishes)
        {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Arrays.equals(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature");
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
            if (!Arrays.equals(parameters, signature))
                throw new IllegalArgumentException("Wrong method signature");
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
