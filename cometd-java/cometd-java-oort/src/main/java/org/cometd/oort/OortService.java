/*
 * Copyright (c) 2013 the original author or authors.
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

package org.cometd.oort;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link OortService} allows applications to forward actions to Oort
 * nodes that own the entity onto which the action should be applied.
 * <p/>
 * An {@link OortService} builds on the concept introduced by {@link OortObject}
 * that the ownership of a particular entity belongs only to one node.
 * Any node can read the entity, but only the owner can create/modify/delete it.
 * <p/>
 * In order to perform actions that modify the entity, a node has to know
 * what is the node that owns the entity, and then forward the action to
 * the owner node.
 * <p/>
 * {@link OortService} provides the facilities to forward the action to the
 * owner node and return the result of the action, or its failure.
 * <p/>
 * {@link OortService}s are usually created at application startup, but may
 * be created and destroyed on-the-fly.
 * In both cases, they must be {@link #start() started} to make them functional
 * and {@link #stop() stopped} when they are no longer needed.
 * <p />
 * Usage of {@link OortService} follows these steps:
 * <ol>
 * <li>
 * The application running in the <em>requesting node</em> identifies
 * the <em>owner node</em> and calls
 * {@link #forward(String, Object, Object)}, passing the Oort URL of
 * the owner node, the action data, and an opaque context
 * </li>
 * <li>
 * The application implements {@link #onForward(Object)}, which is
 * executed in the <em>owner node</em>. In this method the action
 * data is used to perform the action that modifies the entity, and
 * the result is returned (or an exception thrown in case of failure).
 * </li>
 * <li>
 * The application implements {@link #onForwardSucceeded(Object, Object)}
 * (which is executed in the <em>requesting node</em>)
 * which provides as parameters the result of the action from the
 * second step and the opaque context from the first step.
 * </li>
 * <li>
 * The application implements {@link #onForwardFailed(Object, Object)}
 * (which is executed in the <em>requesting node</em>)
 * which provides as parameters the failure from the second step and
 * the opaque context from the first step.
 * The failure object is the message string of a generic exception,
 * or the failure passed to a {@link ServiceException}.
 * </li>
 * </ol>
 * The steps above do not change if the <em>requesting node</em> and
 * the <em>owner node</em> are the same.
 *
 * @param <R> the result type
 * @param <C> the opaque context type
 */
public abstract class OortService<R, C> extends AbstractLifeCycle implements ServerChannel.MessageListener
{
    private static final String ACTION_FIELD = "oort.service.action";
    private static final String ID_FIELD = "oort.service.id";
    private static final String OORT_URL_FIELD = "oort.service.url";
    private static final String RESULT_FIELD = "oort.service.result";
    private static final String FAILURE_FIELD = "oort.service.failure";

    private final AtomicLong actions = new AtomicLong();
    private final ConcurrentMap<Long, C> callbacks = new ConcurrentHashMap<>();
    private final Oort oort;
    private final String name;
    private final String forwardChannelName;
    private final String resultChannelName;
    private final LocalSession session;
    protected final Logger logger;

    /**
     * Creates an {@link OortService} with the given name.
     *
     * @param oort the Oort where this service lives
     * @param name the unique name across the cluster of this service
     */
    protected OortService(Oort oort, String name)
    {
        this.oort = oort;
        this.name = name;
        this.forwardChannelName = "/service/oort/service/" + name;
        this.resultChannelName = forwardChannelName + "/result";
        this.session = oort.getBayeuxServer().newLocalSession(name);
        this.logger = LoggerFactory.getLogger(getLoggerName());
    }

    protected String getLoggerName()
    {
        return getClass().getName();
    }

    /**
     * @return the Oort of this service
     */
    public Oort getOort()
    {
        return oort;
    }

    /**
     * @return the name of this service
     */
    public String getName()
    {
        return name;
    }

    /**
     * @return the local session associated with this service
     */
    public LocalSession getLocalSession()
    {
        return session;
    }

    @Override
    protected void doStart() throws Exception
    {
        session.handshake();
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        bayeuxServer.createIfAbsent(forwardChannelName);
        bayeuxServer.getChannel(forwardChannelName).addListener(this);
        bayeuxServer.createIfAbsent(resultChannelName);
        bayeuxServer.getChannel(resultChannelName).addListener(this);
        logger.debug("Started {}", this);
    }

    @Override
    protected void doStop() throws Exception
    {
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        bayeuxServer.getChannel(resultChannelName).removeListener(this);
        bayeuxServer.getChannel(forwardChannelName).removeListener(this);
        session.disconnect();
        logger.debug("Stopped {}", this);
    }

    /**
     * Subclasses must call this method to forward the action to the owner node.
     *
     * @param targetOortURL the owner node Oort URL
     * @param actionData the action data that will be passed to {@link #onForward(Object)}
     * @param context the opaque context passed to {@link #onForwardSucceeded(Object, Object)}
     * @return whether the forward succeeded
     */
    protected boolean forward(String targetOortURL, Object actionData, C context)
    {
        long actionId = actions.incrementAndGet();
        if (context != null)
            callbacks.put(actionId, context);
        Map<String, Object> data = new HashMap<>(3);
        data.put(ID_FIELD, actionId);
        data.put(ACTION_FIELD, actionData);
        String localOortURL = getOort().getURL();
        data.put(OORT_URL_FIELD, localOortURL);

        if (localOortURL.equals(targetOortURL))
        {
            // Local case
            logger.debug("Forwarding action locally ({}): {}", localOortURL, data);
            oort.getBayeuxServer().getChannel(forwardChannelName).publish(getLocalSession(), data);
            return true;
        }
        else
        {
            // Remote case
            if (targetOortURL != null)
            {
                OortComet comet = getOort().getComet(targetOortURL);
                if (comet != null)
                {
                    logger.debug("Forwarding action from {} to {}: {}", localOortURL, targetOortURL, data);
                    comet.getChannel(forwardChannelName).publish(data);
                    return true;
                }
            }
            logger.debug("Could not forward action from {} to {}: {}", localOortURL, targetOortURL, data);
            return false;
        }
    }

    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
    {
        if (forwardChannelName.equals(message.getChannel()))
        {
            logger.debug("Received forwarded action {}", message);
            Map<String, Object> data = message.getDataAsMap();
            Map<String, Object> resultData = new HashMap<>(3);
            resultData.put(ID_FIELD, data.get(ID_FIELD));
            resultData.put(OORT_URL_FIELD, getOort().getURL());
            try
            {
                R result = onForward(data.get(ACTION_FIELD));
                resultData.put(RESULT_FIELD, result);
            }
            catch (ServiceException x)
            {
                resultData.put(FAILURE_FIELD, x.getFailure());
            }
            catch (Exception x)
            {
                String failure = x.getMessage();
                if (failure == null || failure.length() == 0)
                    failure = x.getClass().getName();
                resultData.put(FAILURE_FIELD, failure);
            }

            String oortURL = (String)data.get(OORT_URL_FIELD);
            if (getOort().getURL().equals(oortURL))
            {
                // Local case
                logger.debug("Returning forwarded action result {} to local {}", resultData, oortURL);
                oort.getBayeuxServer().getChannel(resultChannelName).publish(getLocalSession(), resultData);
            }
            else
            {
                // Remote case
                OortComet comet = getOort().getComet(oortURL);
                if (comet != null)
                {
                    logger.debug("Returning forwarded action result {} to remote {}", resultData, oortURL);
                    comet.getChannel(resultChannelName).publish(resultData);
                }
                else
                {
                    logger.debug("Could not return forwarded action result {} to remote {}", resultData, oortURL);
                }
            }
        }
        else if (resultChannelName.equals(message.getChannel()))
        {
            logger.debug("Received forwarded action result {}", message);
            Map<String, Object> data = message.getDataAsMap();
            long actionId = ((Number)data.get(ID_FIELD)).longValue();
            C context = callbacks.remove(actionId);
            if (context != null)
            {
                Object failure = data.get(FAILURE_FIELD);
                if (failure != null)
                {
                    onForwardFailed(failure, context);
                }
                else
                {
                    @SuppressWarnings("unchecked")
                    R result = (R)data.get(RESULT_FIELD);
                    onForwardSucceeded(result, context);
                }
            }
        }
        return true;
    }

    /**
     * Subclasses must implement this method, that runs on the <em>owner node</em>,
     * to implement the action functionality.
     *
     * @param actionData the action data passed from {@link #forward(String, Object, Object)}
     * @return the action result that will be passed to {@link #onForwardSucceeded(Object, Object)}
     */
    protected abstract R onForward(Object actionData);

    /**
     * Subclasses must implement this method, that runs on the <em>requesting node</em>,
     * to complete the functionality after the action has been successfully run on the <em>owner node</em>.
     *
     * @param result the result of the action
     * @param context the opaque context from {@link #forward(String, Object, Object)}
     */
    protected abstract void onForwardSucceeded(R result, C context);

    /**
     * Subclasses must implement this method, that runs on the <em>requesting node</em>,
     * to complete the functionality after the action failed on the <em>owner node</em>.
     *
     * @param failure the failure of the action
     * @param context the opaque context from {@link #forward(String, Object, Object)}
     */
    protected abstract void onForwardFailed(Object failure, C context);

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%s", getClass().getSimpleName(), getName(), getOort().getURL());
    }

    /**
     * Subclasses may throw this exception from {@link #onForward(Object)} if they want
     * to produce a complex failure object that will be reported to {@link #onForwardFailed(Object, Object)}.
     */
    public static class ServiceException extends RuntimeException
    {
        private final Object failure;

        public ServiceException(Object failure)
        {
            this.failure = failure;
        }

        public ServiceException(Throwable cause, Object failure)
        {
            super(cause);
            this.failure = failure;
        }

        public Object getFailure()
        {
            return failure;
        }
    }

    /**
     * Utility context that stores the {@link ServerSession} and the {@link ServerMessage}.
     * <p />
     * CometD services that extend {@link OortService} may register themselves as listeners
     * for messages sent by remote clients. In such case, this class will come handy in this way:
     * <pre>
     * &#64;Service
     * class MyService extends OortService&lt;Boolean, ServerContext&gt;
     * {
     *     &#64;Listener("/service/some")
     *     public void processSome(ServerSession remote, ServerMessage message)
     *     {
     *         String ownerOortURL = findOwnerOortURL();
     *         forward(ownerOortURL, "some", new ServerContext(remote, message));
     *     }
     *
     *     protected Boolean onForward(Object forwardedData)
     *     {
     *         return "some".equals(forwardedData);
     *     }
     *
     *     protected void onForwardSucceeded(Boolean result, ServerContext context)
     *     {
     *         context.getServerSession().deliver(getLocalSession(), "/service/some", result, null);
     *     }
     *
     *     ...
     * }
     * </pre>
     */
    public static class ServerContext
    {
        private final ServerSession session;
        private final ServerMessage message;

        public ServerContext(ServerSession session, ServerMessage message)
        {
            this.session = session;
            this.message = message;
        }

        public ServerSession getServerSession()
        {
            return session;
        }

        public ServerMessage getServerMessage()
        {
            return message;
        }
    }
}
