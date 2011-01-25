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

import java.lang.reflect.Method;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

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
    private final String _name;
    private final BayeuxServerImpl _bayeux;
    private final LocalSession _session;

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
        if (maxThreads > 0)
            setThreadPool(new QueuedThreadPool(maxThreads));
        _name=name;
        _bayeux=(BayeuxServerImpl)bayeux;
        _session=_bayeux.newLocalSession(name);
        _session.handshake();
        _logger=((BayeuxServerImpl)bayeux).getLogger();
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
     * {@link #send(ServerSession, String, Object, String)} to deliver a response
     * message(s) to different clients and/or channels. It may also publish
     * methods via the normal {@link Bayeux} API.
     * <p>
     *
     *
     * @param channelId
     *            The channel to subscribe to
     * @param methodName
     *            The name of the method on this object to call when messages
     *            are received.
     */
    protected void addService(String channelId, String methodName)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("subscribe "+_name+"#"+methodName+" to "+channelId);

        Method method=null;

        Class<?> c=this.getClass();
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
        int params=method.getParameterTypes().length;
        if (params < 2 || params > 4)
            throw new IllegalArgumentException("Method '" + methodName + "' does not have 2, 3 or 4 parameters");
        if (!ServerSession.class.isAssignableFrom(method.getParameterTypes()[0]))
            throw new IllegalArgumentException("Method '" + methodName + "' does not have Session as first parameter");

        _bayeux.createIfAbsent(channelId);
        ServerChannel channel=_bayeux.getChannel(channelId);

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
     * Typically this method is only required if a service method sends
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
                if (Message.class.isAssignableFrom(parameterTypes[messageParameterIndex]))
                    messageArgument = msg;

                boolean accessible = method.isAccessible();
                Object reply = null;
                try
                {
                    method.setAccessible(true);
                    switch (method.getParameterTypes().length)
                    {
                        case 2:
                            reply = method.invoke(this, fromClient, messageArgument);
                            break;
                        case 3:
                            reply = method.invoke(this, fromClient, messageArgument, id);
                            break;
                        case 4:
                            reply = method.invoke(this, fromClient, channel, messageArgument, id);
                            break;
                    }
                }
                finally
                {
                    method.setAccessible(accessible);
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
