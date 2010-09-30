// ========================================================================
// Copyright 2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================
package org.cometd.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.IllegalSelectorException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.annotation.CometdServer;
import org.cometd.server.annotation.CometdService;
import org.cometd.server.annotation.CometdSession;
import org.cometd.server.annotation.Configure;
import org.cometd.server.annotation.Subscription;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.springframework.beans.MethodInvocationException;

/* ------------------------------------------------------------ */
/**
 * Abstract Bayeux Service
 * <p>
 * This class provides convenience methods to assist with the
 * creation of a Bayeux Services typically used to provide the
 * behaviour associated with a service channel (see {@link Channel#isService()}).
 * Specifically it provides: <ul>
 * <li>Mapping of channel subscriptions to method invocation on the derived service
 * class.
 * <li>Optional use of a thread pool used for method invocation if handling can take
 * considerable time and it is desired not to hold up the delivering thread
 * (typically a HTTP request handling thread).
 * <li>The objects returned from method invocation are delivered back to the
 * calling client in a private message.
 * </ul>
 *
 * @see {@link BayeuxServer#getSession(String)} as an alternative to AbstractService.
 */
public abstract class AbstractService
{
    private final Object _service;
    private final String _name;
    private final BayeuxServerImpl _bayeux;
    private final LocalSession _session;
    private final Map<String,Method> _methods=new ConcurrentHashMap<String,Method>();
    private final Map<ChannelId,Method> _wild=new ConcurrentHashMap<ChannelId,Method>();

    private ThreadPool _threadPool;
    private boolean _seeOwn=false;
    private final Logger _logger;

    /* ------------------------------------------------------------ */
    /**
     * Instantiate the service. Typically the derived constructor will call @
     * #subscribe(String, String)} to map subscriptions to methods.
     *
     * @param bayeux
     *            The bayeux instance.
     * @param name
     *            The name of the service (used as client ID prefix).
     */
    public AbstractService(BayeuxServer bayeux, String name)
    {
        this(bayeux,name,0);
    }


    /* ------------------------------------------------------------ */
    /**
     * Instantiate the service. Typically the derived constructor will call @
     * #subscribe(String, String)} to map subscriptions to methods.
     *
     * @param bayeux
     *            The bayeux instance.
     * @param name
     *            The name of the service (used as client ID prefix).
     * @param maxThreads
     *            The size of a ThreadPool to create to handle messages.
     */
    public AbstractService(BayeuxServer bayeux, String name, int maxThreads)
    {
        _service=this;
        if (maxThreads > 0)
            setThreadPool(new QueuedThreadPool(maxThreads));
        _name=name;
        _bayeux=(BayeuxServerImpl)bayeux;
        _session=_bayeux.newLocalSession(name);
        _session.handshake();
        _logger=((BayeuxServerImpl)bayeux).getLogger();
    }
    
    private AbstractService(Object service,BayeuxServer bayeux, String name, int maxThreads)
    {
        _service=service;
        if (maxThreads > 0)
            setThreadPool(new QueuedThreadPool(maxThreads));
        _name=name;
        _bayeux=(BayeuxServerImpl)bayeux;
        _session=_bayeux.newLocalSession(name);
        _session.handshake();
        _logger=((BayeuxServerImpl)bayeux).getLogger();
    }
    
    public static void register(BayeuxServer bayeux,Object service)
    {
        CometdService s = service.getClass().getAnnotation(CometdService.class);
        if (s==null)
            throw new IllegalArgumentException("!@CometdService: "+service.getClass());
        
        String name=s.name();
        int threads=s.threads();
        boolean see_own = s.seeOwn();
        
        AbstractService as = new AbstractService(service,bayeux,name,threads)
        {    
        };
        
        as.setSeeOwnPublishes(see_own);
        as.init();
    }
    
    protected void init()
    {
        try
        {
            Class<?> clazz = _service.getClass();

            // Look for fields to inject with Bayeux and/or Session
            Class<?> c=clazz;
            while (c!=null)
            {
                for (final Field field : c.getDeclaredFields())
                {
                    System.err.println("field "+field);
                    CometdServer cs = field.getAnnotation(CometdServer.class);
                    if (cs!=null)
                    {
                        System.err.println("field "+cs);
                        boolean access = field.isAccessible();
                        field.setAccessible(true);
                        field.set(_service,_bayeux);
                        field.setAccessible(access);
                    }

                    CometdSession session = field.getAnnotation(CometdSession.class);
                    if (session!=null)
                    {
                        System.err.println("field "+session);
                        boolean access = field.isAccessible();
                        field.setAccessible(true);
                        field.set(_service,_session.getServerSession());
                        field.setAccessible(access);
                    }
                }
                c=c.getSuperclass();
            }
            
            // Look for methods to configure channels with
            for (final Method method : clazz.getMethods())
            {
                Configure configure = method.getAnnotation(Configure.class);
                if (configure!=null)
                {
                    for (final String channel : Arrays.asList(configure.channels()))
                    {
                        // if configure if absent, then use createIfAbsent and error if already created
                        if (configure.ifAbsent())
                        {
                            if (!_bayeux.createIfAbsent(channel,new ServerChannel.Initializer()
                            {
                                public void configureChannel(ConfigurableServerChannel channel)
                                {
                                    try
                                    {
                                        method.invoke(_service,channel);
                                    }
                                    catch(Exception e)
                                    {
                                        _logger.warn(e);
                                        throw new RuntimeException(e);
                                    }
                                }
                            }))
                                throw new IllegalStateException("Channel already created: "+channel);
                        }
                        else // otherwise just configure
                        {
                            method.invoke(_service,_bayeux.getChannel(channel));

                        }

                    }
                }
            }

            // Look for methods that are subscriptions
            for (Method method : clazz.getDeclaredMethods())
            {
                Subscription s= method.getAnnotation(Subscription.class);
                if (s!=null)
                {
                    for (String channel : Arrays.asList(s.channels()))
                    {
                        addService(channel,method);
                    }
                }
            }
        }
        catch(Exception e)
        {
            _logger.warn(e);
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    public BayeuxServer getBayeux()
    {
        return _bayeux;
    }

    /* ------------------------------------------------------------ */
    public LocalSession getLocalSession()
    {
        return _session;
    }

    /* ------------------------------------------------------------ */
    public ServerSession getServerSession()
    {
        return _session.getServerSession();
    }

    /* ------------------------------------------------------------ */
    public ThreadPool getThreadPool()
    {
        return _threadPool;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the threadpool. If the {@link ThreadPool} is a {@link LifeCycle},
     * then it is started by this method.
     *
     * @param pool
     */
    public void setThreadPool(ThreadPool pool)
    {
        try
        {
            if (pool instanceof LifeCycle)
                if (!((LifeCycle)pool).isStarted())
                    ((LifeCycle)pool).start();
        }
        catch(Exception e)
        {
            throw new IllegalStateException(e);
        }
        _threadPool=pool;
    }

    /* ------------------------------------------------------------ */
    public boolean isSeeOwnPublishes()
    {
        return _seeOwn;
    }

    /* ------------------------------------------------------------ */
    public void setSeeOwnPublishes(boolean own)
    {
        _seeOwn=own;
    }

    /* ------------------------------------------------------------ */
    /**
     * Add a service.
     * <p>Listen to a channel and map a method to handle
     * received messages. The method must have a unique name and one of the
     * following signatures:
     * <ul>
     * <li><code>myMethod(ServerSession from,Object data)</code></li>
     * <li><code>myMethod(ServerSession from,Object data,String|Object id)</code></li>
     * <li><code>myMethod(ServerSession from,String channel,Object data,String|Object id)</code>
     * </li>
     * </li>
     *
     * The data parameter can be typed if the type of the data object published
     * by the client is known (typically Map<String,Object>). If the type of the
     * data parameter is {@link Message} then the message object itself is
     * passed rather than just the data.
     * <p>
     * Typically a service will be used to a channel in the "/service/**"
     * space which is not a broadcast channel. Messages published to these
     * channels are only delivered to server side clients like this service.
     * <p>
     * Any object returned by a mapped subscription method is delivered to the
     * calling client and not broadcast. If the method returns void or null,
     * then no response is sent. A mapped subscription method may also call
     * {@link #send(Client, String, Object, String)} to deliver a response
     * message(s) to different clients and/or channels. It may also publish
     * methods via the normal {@link Bayeux} API.
     * <p>
     *
     *
     * @param channelId
     *            The channel to subscribe to
     * @param methodName
     *            The name of the method on this object to call when messages
     *            are recieved.
     */
    protected void addService(String channelId, String methodName)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("subscribe "+_name+"#"+methodName+" to "+channelId);

        Method method=null;

        Class<?> c=_service.getClass();
        while(c != null && c != Object.class)
        {
            Method[] methods=c.getDeclaredMethods();
            for (int i=methods.length; i-- > 0;)
            {
                if (methodName.equals(methods[i].getName()))
                {
                    if (method != null)
                        throw new IllegalArgumentException("Multiple methods called '" + methodName + "'");
                    method=methods[i];
                }
            }
            c=c.getSuperclass();
        }
        
        if (method == null)
            throw new NoSuchMethodError(methodName);
        
        addService(channelId,method);
    }

    /* ------------------------------------------------------------ */
    /**
     * Add a service.
     * <p>Listen to a channel and map a method to handle
     * received messages. The method must have a unique name and one of the
     * following signatures:
     * <ul>
     * <li><code>myMethod(ServerSession from,Object data)</code></li>
     * <li><code>myMethod(ServerSession from,Object data,String|Object id)</code></li>
     * <li><code>myMethod(ServerSession from,String channel,Object data,String|Object id)</code>
     * </li>
     * </li>
     *
     * The data parameter can be typed if the type of the data object published
     * by the client is known (typically Map<String,Object>). If the type of the
     * data parameter is {@link Message} then the message object itself is
     * passed rather than just the data.
     * <p>
     * Typically a service will be used to a channel in the "/service/**"
     * space which is not a broadcast channel. Messages published to these
     * channels are only delivered to server side clients like this service.
     * <p>
     * Any object returned by a mapped subscription method is delivered to the
     * calling client and not broadcast. If the method returns void or null,
     * then no response is sent. A mapped subscription method may also call
     * {@link #send(Client, String, Object, String)} to deliver a response
     * message(s) to different clients and/or channels. It may also publish
     * methods via the normal {@link Bayeux} API.
     * <p>
     *
     *
     * @param channelId
     *            The channel to subscribe to
     * @param methodName
     *            The name of the method on this object to call when messages
     *            are recieved.
     */
    protected void addService(String channelId, Method method)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("subscribe "+_name+"#"+method.getName()+" to "+channelId);

        int params=method.getParameterTypes().length;
        if (params < 2 || params > 4)
            throw new IllegalArgumentException("Method '" + method + "' does not have 2or3 parameters");
        if (!ServerSession.class.isAssignableFrom(method.getParameterTypes()[0]))
            throw new IllegalArgumentException("Method '" + method + "' does not have Session as first parameter");

        _bayeux.createIfAbsent(channelId);
        ServerChannel channel=_bayeux.getChannel(channelId);

        if (channel.isWild())
            _wild.put(new ChannelId(channel.getId()),method);
        else
            _methods.put(channelId,method);

        final Method invoke=method;
        channel.addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                if (_seeOwn || from != getServerSession())
                    invoke(invoke,from,message);

                return true;
            }
        });

    }

    /* ------------------------------------------------------------ */
    /**
     * Send data to a individual client. The data passed is sent to the client
     * as the "data" member of a message with the given channel and id. The
     * message is not published on the channel and is thus not broadcast to all
     * channel subscribers. However to the target client, the message appears as
     * if it was broadcast.
     * <p>
     * Typcially this method is only required if a service method sends
     * response(s) to channels other than the subscribed channel. If the
     * response is to be sent to the subscribed channel, then the data can
     * simply be returned from the subscription method.
     *
     * @param toClient
     *            The target client
     * @param onChannel
     *            The channel the message is for
     * @param data
     *            The data of the message
     * @param id
     *            The id of the message (or null for a random id).
     */
    protected void send(ServerSession toClient, String onChannel, Object data, String id)
    {
        toClient.deliver(_session.getServerSession(),onChannel,data,id);
    }

    /* ------------------------------------------------------------ */
    /**
     * Handle Exception. This method is called when a mapped subscription method
     * throws and exception while handling a message.
     *
     * @param fromClient
     * @param toClient
     * @param msg
     * @param th
     */
    protected void exception(String method,ServerSession fromClient, LocalSession toClient, ServerMessage msg, Throwable th)
    {
        System.err.println(method+": "+msg);
        th.printStackTrace();
    }

    /* ------------------------------------------------------------ */
    private void invoke(final Method method, final ServerSession fromClient, final ServerMessage msg)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("invoke "+_name+"#"+method.getName()+" from "+fromClient+" with "+msg.getData());

        if (_threadPool == null)
            doInvoke(method,fromClient,msg);
        else
        {
            _threadPool.dispatch(new Runnable()
            {
                public void run()
                {
                    doInvoke(method,fromClient,msg);
                }
            });
        }
    }

    /* ------------------------------------------------------------ */
    protected void doInvoke(Method method, ServerSession fromClient, ServerMessage msg)
    {
        String channel = msg.getChannel();
        Object data = msg.getData();
        String id = msg.getId();

        if (method != null)
        {
            try
            {
                Class<?>[] parameterTypes = method.getParameterTypes();
                int messageParameterIndex = parameterTypes.length == 4 ? 2 : 1;
                Object messageArgument = data;
                if (ServerMessage.class.isAssignableFrom(parameterTypes[messageParameterIndex]) ||
                        Message.class.isAssignableFrom(parameterTypes[messageParameterIndex]))
                {
                    messageArgument = msg;
                }

                method.setAccessible(true);

                Object reply = null;
                switch (method.getParameterTypes().length)
                {
                    case 2:
                        reply = method.invoke(_service, fromClient, messageArgument);
                        break;
                    case 3:
                        reply = method.invoke(_service, fromClient, messageArgument, id);
                        break;
                    case 4:
                        reply = method.invoke(_service, fromClient, channel, messageArgument, id);
                        break;
                }

                if (reply != null)
                    send(fromClient, channel, reply, id);
            }
            catch (Exception e)
            {
                exception(method.toString(), fromClient, _session, msg, e);
            }
            catch (Error e)
            {
                exception(method.toString(), fromClient, _session, msg, e);
            }
        }
    }
}
