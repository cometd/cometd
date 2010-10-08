package org.cometd.java.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;

public class ClientAnnotationProcessor extends AnnotationProcessor
{
    private static final ConcurrentMap<ClientSession, ClientAnnotationProcessor> instances = new ConcurrentHashMap<ClientSession, ClientAnnotationProcessor>();

    public static ClientAnnotationProcessor get(ClientSession clientSession)
    {
        ClientAnnotationProcessor result = instances.get(clientSession);
        if (result == null)
        {
            result = new ClientAnnotationProcessor(clientSession);
            ClientAnnotationProcessor existing = instances.putIfAbsent(clientSession, result);
            if (existing != null)
                result = existing;
        }
        return result;
    }

    private final ConcurrentMap<Object, List<ListenerCallback>> listeners = new ConcurrentHashMap<Object, List<ListenerCallback>>();
    private final ConcurrentMap<Object, List<SubscriptionCallback>> subscribers = new ConcurrentHashMap<Object, List<SubscriptionCallback>>();
    private final ClientSession clientSession;

    private ClientAnnotationProcessor(ClientSession clientSession)
    {
        this.clientSession = clientSession;
    }

    public void close()
    {
        instances.remove(clientSession);
    }

    public boolean configure(Object bean)
    {
        boolean result = configureDependencies(bean);
        result |= configureCallbacks(bean);
        return result;
    }

    private boolean configureCallbacks(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        boolean result = configureListener(bean);
        result |= configureSubscription(bean);
        return result;
    }

    public boolean deconfigureCallbacks(Object bean)
    {
        boolean result = deconfigureListener(bean);
        result |= deconfigureSubscription(bean);
        return result;
    }

    private boolean configureDependencies(Object bean)
    {
        if (bean == null)
            return false;

        Class<?> klass = bean.getClass();
        Service serviceAnnotation = klass.getAnnotation(Service.class);
        if (serviceAnnotation == null)
            return false;

        return configureSession(bean, clientSession);
    }

    private boolean configureSession(Object bean, ClientSession clientSession)
    {
        boolean result = false;
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass())
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
                        logger.debug("Injected {} to field {} on bean {}", clientSession, field, bean);
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
                            logger.debug("Injected {} to method {} on bean {}", clientSession, method, bean);
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean configureListener(Object bean)
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

    private boolean configureSubscription(Object bean)
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
            if (!Arrays.equals(parameters, signature))
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
            if (!Arrays.equals(parameters, signature))
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
