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

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link OortService} allows applications to forward actions to Oort
 * nodes that own the entity onto which the action should be applied.
 * <p/>
 * An {@link OortService} builds on the concept introduced by {@link OortObject}
 * that the ownership of an entity belongs only to one node. Any node can read
 * the entity, but only the owner can create/modify/delete it.
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
public abstract class OortService<R, C> implements ClientSessionChannel.MessageListener
{
    private static final String ACTION = "org.cometd.oort.OortService.action";
    private static final String ACTION_ID = "org.cometd.oort.OortService.actionId";
    private static final String OORT_URL = "org.cometd.oort.OortService.oortURL";
    private static final String RESULT = "org.cometd.oort.OortService.result";
    private static final String FAILURE = "org.cometd.oort.OortService.failure";

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicLong actions = new AtomicLong();
    private final ConcurrentMap<Long, C> callbacks = new ConcurrentHashMap<Long, C>();
    private final Oort oort;
    private final String name;
    private final String forwardChannel;
    private final String resultChannel;
    private final LocalSession session;
    private final Seti forwarder;

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
        this.forwardChannel = "/service/seti/" + name;
        this.resultChannel = forwardChannel + "/result";
        this.session = oort.getBayeuxServer().newLocalSession(name);
        this.forwarder = new Seti(oort);
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

    /**
     * Starts the functionalities offered by this service.
     *
     * @throws Exception if the start fails
     * @see #stop()
     */
    public void start() throws Exception
    {
        session.handshake();
        forwarder.start();
        forwarder.associate(getOort().getURL(), session.getServerSession());
        session.getChannel(forwardChannel).subscribe(this);
        session.getChannel(resultChannel).subscribe(this);
        logger.debug("Started {}", this);
    }

    /**
     * Stops the functionalities offered by this service.
     *
     * @throws Exception if the stop fails
     * @see #start()
     */
    public void stop() throws Exception
    {
        forwarder.disassociate(getOort().getURL(), session.getServerSession());
        forwarder.stop();
        session.disconnect();
        logger.debug("Stopped {}", this);
    }

    /**
     * Subclasses must call this method to forward the action to the owner node.
     *
     * @param oortURL the owner node Oort URL
     * @param actionData the action data that will be passed to {@link #onForward(Object)}
     * @param context the opaque context passed to {@link #onForwardSucceeded(Object, Object)}
     */
    protected void forward(String oortURL, Object actionData, C context)
    {
        long actionId = actions.incrementAndGet();
        if (context != null)
            callbacks.put(actionId, context);
        Map<String, Object> data = new HashMap<String, Object>(3);
        data.put(ACTION_ID, actionId);
        data.put(ACTION, actionData);
        data.put(OORT_URL, getOort().getURL());
        logger.debug("Forwarding action to {}", oortURL);
        forwarder.sendMessage(oortURL, "/service/seti/" + getName(), data);
    }

    public void onMessage(ClientSessionChannel clientSessionChannel, Message message)
    {
        if (forwardChannel.equals(message.getChannel()))
        {
            logger.debug("Received forwarded action {}", message);
            Map<String, Object> data = message.getDataAsMap();
            Map<String, Object> resultData = new HashMap<String, Object>(3);
            resultData.put(ACTION_ID, data.get(ACTION_ID));
            resultData.put(OORT_URL, getOort().getURL());
            try
            {
                R result = onForward(data.get(ACTION));
                resultData.put(RESULT, result);
            }
            catch (ServiceException x)
            {
                resultData.put(FAILURE, x.getFailure());
            }
            catch (Exception x)
            {
                String failure = x.getMessage();
                if (failure == null || failure.length() == 0)
                    failure = x.getClass().getName();
                resultData.put(FAILURE, failure);
            }
            forwarder.sendMessage((String)data.get(OORT_URL), resultChannel, resultData);
        }
        else if (resultChannel.equals(message.getChannel()))
        {
            logger.debug("Received forwarded result {}", message);
            Map<String, Object> data = message.getDataAsMap();
            long actionId = ((Number)data.get(ACTION_ID)).longValue();
            C context = callbacks.remove(actionId);
            if (context != null)
            {
                Object failure = data.get(FAILURE);
                if (failure != null)
                {
                    onForwardFailed(failure, context);
                }
                else
                {
                    @SuppressWarnings("unchecked")
                    R result = (R)data.get(RESULT);
                    onForwardSucceeded(result, context);
                }
            }
        }
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
        return String.format("%s[%s]", getClass().getSimpleName(), getName());
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
