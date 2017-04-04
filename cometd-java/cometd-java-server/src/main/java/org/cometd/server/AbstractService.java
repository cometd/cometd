/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>{@link AbstractService} provides convenience methods to assist with the
 * creation of a CometD services.</p>
 * <p>A CometD service runs application code whenever a message is received on
 * a particular channel.</p>
 * <p>Specifically it provides:</p>
 * <ul>
 * <li>Mapping of channel subscriptions to method invocation on the derived
 * service class.</li>
 * <li>Optional use of a thread pool used for method invocation if handling
 * can take considerable time and it is desired not to hold up the delivering
 * thread (typically a HTTP request handling thread).</li>
 * <li>The objects returned from method invocation are delivered back to the
 * calling client in a private message.</li>
 * </ul>
 * <p>Subclasses should call {@link #addService(String, String)} in order to
 * map channel subscriptions to method invocations, usually in the subclass
 * constructor.</p>
 * <p>Each CometD service has an associated {@link LocalSession} that can be
 * used as the source for messages published via
 * {@link ServerChannel#publish(Session, ServerMessage.Mutable)} or
 * {@link ServerSession#deliver(Session, ServerMessage.Mutable)}.</p>
 *
 * @see BayeuxServer#newLocalSession(String)
 */
public abstract class AbstractService {
    protected final Logger _logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Invoker> invokers = new ConcurrentHashMap<>();
    private final String _name;
    private final BayeuxServerImpl _bayeux;
    private final LocalSession _session;
    private ThreadPool _threadPool;
    private boolean _seeOwn = false;

    /**
     * <p>Instantiates a CometD service with the given name.</p>
     *
     * @param bayeux The BayeuxServer instance.
     * @param name   The name of the service (used as client ID prefix).
     */
    public AbstractService(BayeuxServer bayeux, String name) {
        this(bayeux, name, 0);
    }

    /**
     * <p>Instantiate a CometD service with the given name and max number of pooled threads.</p>
     *
     * @param bayeux     The BayeuxServer instance.
     * @param name       The name of the service (used as client ID prefix).
     * @param maxThreads The max size of a ThreadPool to create to handle messages.
     */
    public AbstractService(BayeuxServer bayeux, String name, int maxThreads) {
        _name = name;
        _bayeux = (BayeuxServerImpl)bayeux;
        _session = _bayeux.newLocalSession(name);
        _session.handshake();
        if (maxThreads > 0) {
            setThreadPool(new QueuedThreadPool(maxThreads));
        }
        if (!Modifier.isPublic(getClass().getModifiers())) {
            throw new IllegalArgumentException("Service class '" + getClass().getName() + "' must be public");
        }
    }

    public BayeuxServer getBayeux() {
        return _bayeux;
    }

    public String getName() {
        return _name;
    }

    /**
     * @return The {@link LocalSession} associated with this CometD service
     */
    public LocalSession getLocalSession() {
        return _session;
    }

    /**
     * @return The {@link ServerSession} of the {@link LocalSession} associated
     * with this CometD service
     */
    public ServerSession getServerSession() {
        return _session.getServerSession();
    }

    /**
     * @return The thread pool associated with this CometD service, or null
     * @see #AbstractService(BayeuxServer, String, int)
     */
    public ThreadPool getThreadPool() {
        return _threadPool;
    }

    /**
     * <p>Sets the thread pool associated to this CometD service.</p>
     * <p>If the {@link ThreadPool} is a {@link LifeCycle} instance,
     * and it is not already started, then it will started.</p>
     *
     * @param pool The ThreadPool
     */
    public void setThreadPool(ThreadPool pool) {
        try {
            if (pool instanceof LifeCycle) {
                if (!((LifeCycle)pool).isStarted()) {
                    ((LifeCycle)pool).start();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        _threadPool = pool;
    }

    /**
     * @return whether this CometD service receives messages published by itself
     * on channels it is subscribed to (defaults to false).
     * @see #setSeeOwnPublishes(boolean)
     */
    public boolean isSeeOwnPublishes() {
        return _seeOwn;
    }

    /**
     * @param seeOwnPublishes whether this CometD service receives messages published by itself
     *                        on channels it is subscribed to (defaults to false).
     * @see #isSeeOwnPublishes()
     */
    public void setSeeOwnPublishes(boolean seeOwnPublishes) {
        _seeOwn = seeOwnPublishes;
    }

    /**
     * <p>Maps the method of a subclass with the given name to a
     * {@link ServerChannel.MessageListener} on the given channel, so that the method
     * is invoked for each message received on the channel.</p>
     * <p>The channel name may be a {@link ServerChannel#isWild() wildcard channel name}.</p>
     * <p>The method must have a unique name and the following signature:</p>
     * <ul>
     * <li><code>myMethod(ServerSession from, ServerMessage message)</code></li>
     * </ul>
     * <p>Typically a service will be used to a channel in the <code>/service/**</code>
     * space which is not a broadcast channel.</p>
     * <p>Any object returned by a mapped method is delivered back to the
     * client that sent the message and not broadcast. If the method returns void or null,
     * then no response is sent.</p>
     * <p>A mapped method may also call {@link #send(org.cometd.bayeux.server.ServerSession, String, Object)}
     * to deliver message(s) to specific clients and/or channels.</p>
     * <p>A mapped method may also publish to different channels via
     * {@link ServerChannel#publish(Session, ServerMessage.Mutable)}.</p>
     *
     * @param channelName The channel to listen to
     * @param methodName  The name of the method on this subclass to call when messages
     *                    are received on the channel
     * @see #removeService(String, String)
     */
    protected void addService(String channelName, String methodName) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Mapping {}#{} to {}", _name, methodName, channelName);
        }

        Method candidate = null;
        Class<?> c = this.getClass();
        while (c != null && c != AbstractService.class) {
            Method[] methods = c.getDeclaredMethods();
            for (int i = methods.length; i-- > 0; ) {
                Method method = methods[i];
                if (methodName.equals(method.getName()) && Modifier.isPublic(method.getModifiers())) {
                    if (candidate != null) {
                        throw new IllegalArgumentException("Multiple service methods called '" + methodName + "'");
                    }
                    candidate = method;
                }
            }
            c = c.getSuperclass();
        }

        if (candidate == null) {
            throw new NoSuchMethodError("Cannot find public service method '" + methodName + "'");
        }
        int params = candidate.getParameterTypes().length;
        if (params != 2) {
            throw new IllegalArgumentException("Service method '" + methodName + "' must have 2 parameters");
        }
        if (!ServerSession.class.isAssignableFrom(candidate.getParameterTypes()[0])) {
            throw new IllegalArgumentException("Service method '" + methodName + "' does not have " + ServerSession.class.getName() + " as first parameter");
        }
        if (!ServerMessage.class.isAssignableFrom(candidate.getParameterTypes()[1])) {
            throw new IllegalArgumentException("Service method '" + methodName + "' does not have " + ServerMessage.class.getName() + " as second parameter");
        }

        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName).getReference();
        Invoker invoker = new Invoker(channelName, candidate);
        channel.addListener(invoker);
        invokers.put(methodName, invoker);
    }

    /**
     * <p>Unmaps the method with the given name that has been mapped to the given channel.</p>
     *
     * @param channelName The channel name
     * @param methodName  The name of the method to unmap
     * @see #addService(String, String)
     * @see #removeService(String)
     */
    protected void removeService(String channelName, String methodName) {
        ServerChannel channel = _bayeux.getChannel(channelName);
        if (channel != null) {
            Invoker invoker = invokers.remove(methodName);
            channel.removeListener(invoker);
        }
    }

    /**
     * <p>Unmaps all the methods that have been mapped to the given channel.</p>
     *
     * @param channelName The channel name
     * @see #addService(String, String)
     * @see #removeService(String, String)
     */
    protected void removeService(String channelName) {
        ServerChannel channel = _bayeux.getChannel(channelName);
        if (channel != null) {
            for (Invoker invoker : invokers.values()) {
                if (invoker.channelName.equals(channelName)) {
                    channel.removeListener(invoker);
                }
            }
        }
    }

    /**
     * <p>Sends data to an individual remote client.</p>
     * <p>The data passed is sent to the client
     * as the "data" member of a message with the given channel. The
     * message is not published on the channel and is thus not broadcast to all
     * channel subscribers, but instead delivered directly to the target client.</p>
     * <p>Typically this method is only required if a service method sends
     * response(s) to clients other than the sender, or on different channels.
     * If the response is to be sent to the sender on the same channel,
     * then the data can simply be the return value of the method.</p>
     *
     * @param toClient  The target client
     * @param onChannel The channel of the message
     * @param data      The data of the message
     */
    protected void send(ServerSession toClient, String onChannel, Object data) {
        toClient.deliver(_session.getServerSession(), onChannel, data);
    }

    /**
     * <p>Handles exceptions during the invocation of a mapped method.</p>
     * <p>This method is called when a mapped method throws and exception while handling a message.</p>
     *
     * @param method  the name of the method invoked that threw an exception
     * @param session the remote session that sent the message
     * @param local   the local session associated to this service
     * @param message the message sent by the remote session
     * @param x       the exception thrown
     */
    protected void exception(String method, ServerSession session, LocalSession local, ServerMessage message, Throwable x) {
        _logger.info("Exception while invoking " + _name + "#" + method + " from " + session + " with " + message, x);
    }

    private void invoke(final Method method, final ServerSession fromClient, final ServerMessage msg) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Invoking {}#{} from {} with {}", _name, method.getName(), fromClient, msg);
        }

        ThreadPool threadPool = getThreadPool();
        if (threadPool == null) {
            doInvoke(method, fromClient, msg);
        } else {
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    doInvoke(method, fromClient, msg);
                }
            });
        }
    }

    protected void doInvoke(Method method, ServerSession session, ServerMessage message) {
        try {
            Object reply = method.invoke(this, session, message);
            if (reply != null) {
                send(session, message.getChannel(), reply);
            }
        } catch (Throwable x) {
            exception(method.toString(), session, _session, message, x);
        }
    }

    private class Invoker implements ServerChannel.MessageListener {
        private final String channelName;
        private final Method method;

        public Invoker(String channelName, Method method) {
            this.channelName = channelName;
            this.method = method;
        }

        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            if (isSeeOwnPublishes() || from != getServerSession()) {
                invoke(method, from, message);
            }
            return true;
        }
    }
}
