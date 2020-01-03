/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An {@link OortService} allows applications to forward actions to Oort
 * nodes that own the entity onto which the action should be applied.</p>
 * <p>An {@link OortService} builds on the concept introduced by {@link OortObject}
 * that the ownership of a particular entity belongs only to one node.
 * Any node can read the entity, but only the owner can create/modify/delete it.</p>
 * <p>In order to perform actions that modify the entity, a node has to know
 * what is the node that owns the entity, and then forward the action to
 * the owner node.</p>
 * <p>{@link OortService} provides the facilities to forward the action to the
 * owner node and return the result of the action, or its failure.</p>
 * <p>{@link OortService}s are usually created at application startup, but may
 * be created and destroyed on-the-fly.
 * In both cases, they must be {@link #start() started} to make them functional
 * and {@link #stop() stopped} when they are no longer needed.</p>
 * <p>Usage of {@link OortService} follows these steps:</p>
 * <ol>
 * <li>
 * The application running in the <em>requesting node</em> identifies
 * the <em>owner node</em> and calls
 * {@link #forward(String, Object, Object)}, passing the Oort URL of
 * the owner node, the action data, and an opaque context
 * </li>
 * <li>
 * The application implements {@link #onForward(Request)}, which is
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
 * or the failure returned by {@link Result#failure(Object)}.
 * </li>
 * </ol>
 * <p>The steps above do not change if the <em>requesting node</em> and
 * the <em>owner node</em> are the same.</p>
 *
 * @param <R> the result type
 * @param <C> the opaque context type
 */
public abstract class OortService<R, C> extends AbstractLifeCycle implements ServerChannel.MessageListener {
    private static final String CONTEXT_FIELD = "oort.service.context";
    private static final String DATA_FIELD = "oort.service.data";
    private static final String ID_FIELD = "oort.service.id";
    private static final String OORT_URL_FIELD = "oort.service.url";
    private static final String PARAMETER_FIELD = "oort.service.parameter";
    private static final String RESULT_FIELD = "oort.service.result";
    private static final String TIMEOUT_FIELD = "oort.service.timeout";

    private final AtomicLong contextIds = new AtomicLong();
    private final ConcurrentMap<Long, Map<String, Object>> callbacks = new ConcurrentHashMap<>();
    private final Oort oort;
    private final String name;
    private final String forwardChannelName;
    private final String broadcastChannelName;
    private final String resultChannelName;
    private final LocalSession session;
    protected final Logger logger;
    private volatile long timeout = 5000;

    /**
     * Creates an {@link OortService} with the given name.
     *
     * @param oort the Oort where this service lives
     * @param name the unique name across the cluster of this service
     */
    protected OortService(Oort oort, String name) {
        this.oort = oort;
        this.name = name;
        this.forwardChannelName = "/service/oort/service/" + name;
        this.broadcastChannelName = "/oort/service/" + name;
        this.resultChannelName = forwardChannelName + "/result";
        this.session = oort.getBayeuxServer().newLocalSession(name);
        this.logger = LoggerFactory.getLogger(getClass().getName() + "." + Oort.replacePunctuation(oort.getURL(), '_') + name);
    }

    /**
     * @return the Oort of this service
     */
    public Oort getOort() {
        return oort;
    }

    /**
     * @return the name of this service
     */
    public String getName() {
        return name;
    }

    /**
     * @return the local session associated with this service
     */
    public LocalSession getLocalSession() {
        return session;
    }

    /**
     * @return the timeout, in milliseconds, for an action to return a result (by default 5000 ms)
     */
    public long getTimeout() {
        return timeout;
    }

    /**
     * @param timeout the timeout, in milliseconds, for an action to return a result
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    @Override
    protected void doStart() throws Exception {
        session.handshake();
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        bayeuxServer.createChannelIfAbsent(forwardChannelName).getReference().addListener(this);
        bayeuxServer.createChannelIfAbsent(broadcastChannelName).getReference().addListener(this);
        bayeuxServer.createChannelIfAbsent(resultChannelName).getReference().addListener(this);
        oort.observeChannel(broadcastChannelName);
        if (logger.isDebugEnabled()) {
            logger.debug("Started {}", this);
        }
    }

    @Override
    protected void doStop() throws Exception {
        oort.deobserveChannel(broadcastChannelName);
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        ServerChannel channel = bayeuxServer.getChannel(resultChannelName);
        if (channel != null) {
            channel.removeListener(this);
        }
        channel = bayeuxServer.getChannel(broadcastChannelName);
        if (channel != null) {
            channel.removeListener(this);
        }
        channel = bayeuxServer.getChannel(forwardChannelName);
        if (channel != null) {
            channel.removeListener(this);
        }
        session.disconnect();
        if (logger.isDebugEnabled()) {
            logger.debug("Stopped {}", this);
        }
    }

    /**
     * <p>Subclasses must call this method to forward the action to the owner node.</p>
     * <p>If the {@code targetOortURL} is {@code null}, then the action is broadcast to all nodes.
     * Nodes that receive an action request that they can't fullfill because they don't own the
     * entity the action should be applied to must return {@link Result#ignore(Object)}.</p>
     *
     * @param targetOortURL the owner node Oort URL, or null to broadcast the action to all nodes
     * @param parameter     the action parameter that will be passed to {@link #onForward(Request)}
     * @param context       the opaque context passed to {@link #onForwardSucceeded(Object, Object)}
     * @return whether the forward succeeded
     */
    protected boolean forward(String targetOortURL, Object parameter, C context) {
        Map<String, Object> ctx = new HashMap<>(4);
        long contextId = contextIds.incrementAndGet();
        ctx.put(ID_FIELD, contextId);
        ctx.put(CONTEXT_FIELD, context);
        callbacks.put(contextId, ctx);

        Map<String, Object> data = new HashMap<>(3);
        data.put(ID_FIELD, contextId);
        data.put(PARAMETER_FIELD, parameter);
        String localOortURL = getOort().getURL();
        data.put(OORT_URL_FIELD, localOortURL);

        if (targetOortURL == null) {
            // Application does not know where the entity is, broadcast
            if (logger.isDebugEnabled()) {
                logger.debug("Broadcasting action: {}", data);
            }
            startTimeout(ctx);
            oort.getBayeuxServer().getChannel(broadcastChannelName).publish(getLocalSession(), data, Promise.noop());
            return true;
        } else {
            if (localOortURL.equals(targetOortURL)) {
                // Local case
                if (logger.isDebugEnabled()) {
                    logger.debug("Forwarding action locally ({}): {}", localOortURL, data);
                }
                startTimeout(ctx);
                onForwardMessage(data, false);
                return true;
            } else {
                // Remote case
                OortComet comet = getOort().getComet(targetOortURL);
                if (comet != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Forwarding action from {} to {}: {}", localOortURL, targetOortURL, data);
                    }
                    startTimeout(ctx);
                    comet.getChannel(forwardChannelName).publish(data);
                    return true;
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Could not forward action from {} to {}: {}", localOortURL, targetOortURL, data);
                    }
                    return false;
                }
            }
        }
    }

    @Override
    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
        if (forwardChannelName.equals(message.getChannel())) {
            onForwardMessage(message.getDataAsMap(), false);
        } else if (broadcastChannelName.equals(message.getChannel())) {
            onForwardMessage(message.getDataAsMap(), true);
        } else if (resultChannelName.equals(message.getChannel())) {
            onResultMessage(message.getDataAsMap());
        }
        return true;
    }

    protected void onForwardMessage(Map<String, Object> data, boolean broadcast) {
        if (logger.isDebugEnabled()) {
            logger.debug("Received {} action {}", broadcast ? "broadcast" : "forwarded", data);
        }
        Map<String, Object> resultData = new HashMap<>(3);
        resultData.put(ID_FIELD, data.get(ID_FIELD));
        resultData.put(OORT_URL_FIELD, getOort().getURL());
        String oortURL = (String)data.get(OORT_URL_FIELD);
        try {
            Result<R> result = onForward(new Request(oort.getURL(), data.get(PARAMETER_FIELD), oortURL));
            if (logger.isDebugEnabled()) {
                logger.debug("Forwarded action result {}", result);
            }
            if (result.succeeded()) {
                resultData.put(RESULT_FIELD, true);
                resultData.put(DATA_FIELD, result.data);
            } else if (result.failed()) {
                resultData.put(RESULT_FIELD, false);
                resultData.put(DATA_FIELD, result.data);
            } else {
                if (broadcast) {
                    // Ignore and therefore return
                    if (logger.isDebugEnabled()) {
                        logger.debug("Ignoring broadcast action result {}", result);
                    }
                    return;
                } else {
                    // Convert ignore into failure
                    resultData.put(RESULT_FIELD, false);
                    resultData.put(DATA_FIELD, result.data);
                }
            }
        } catch (Throwable x) {
            if (broadcast) {
                return;
            }

            String failure = x.getMessage();
            if (failure == null || failure.length() == 0) {
                failure = x.getClass().getName();
            }
            resultData.put(RESULT_FIELD, false);
            resultData.put(DATA_FIELD, failure);
        }

        if (getOort().getURL().equals(oortURL)) {
            // Local case
            if (logger.isDebugEnabled()) {
                logger.debug("Returning forwarded action result {} to local {}", resultData, oortURL);
            }
            onResultMessage(resultData);
        } else {
            // Remote case
            OortComet comet = getOort().getComet(oortURL);
            if (comet != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Returning forwarded action result {} to remote {}", resultData, oortURL);
                }
                comet.getChannel(resultChannelName).publish(resultData);
            } else {
                // Probably the node disconnected concurrently
                if (logger.isDebugEnabled()) {
                    logger.debug("Could not return forwarded action result {} to remote {}", resultData, oortURL);
                }
            }
        }
    }

    protected void onResultMessage(Map<String, Object> data) {
        long actionId = ((Number)data.get(ID_FIELD)).longValue();
        Map<String, Object> ctx = callbacks.remove(actionId);
        if (logger.isDebugEnabled()) {
            logger.debug("Action result {} with context {}", data, ctx);
        }
        // Atomically remove the callback, so we guarantee one notification only.
        // Multiple notifications may happen when broadcasting the forward request
        // and nodes mistakenly return multiple results.
        if (ctx != null) {
            cancelTimeout(ctx);

            @SuppressWarnings("unchecked")
            C context = (C)ctx.get(CONTEXT_FIELD);
            boolean success = (Boolean)data.get(RESULT_FIELD);
            if (success) {
                @SuppressWarnings("unchecked")
                R result = (R)data.get(DATA_FIELD);
                onForwardSucceeded(result, context);
            } else {
                Object failure = data.get(DATA_FIELD);
                onForwardFailed(failure, context);
            }
        }
    }

    private void startTimeout(Map<String, Object> ctx) {
        long contextId = ((Number)ctx.get(ID_FIELD)).longValue();
        TimeoutTask timeoutTask = new TimeoutTask(contextId);
        ctx.put(TIMEOUT_FIELD, ((BayeuxServerImpl)oort.getBayeuxServer()).schedule(timeoutTask, getTimeout()));
    }

    private void cancelTimeout(Map<String, Object> ctx) {
        Scheduler.Task timeoutTask = (Scheduler.Task)ctx.get(TIMEOUT_FIELD);
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
    }

    /**
     * <p>Subclasses must implement this method, that runs on the <em>owner node</em>,
     * to implement the action functionality.</p>
     * <p>The result to return is {@link Result#success(Object)} or {@link Result#failure(Object)}
     * if the implementation of this method was able to find the entity on which the action
     * functionality was meant to be applied, or {@link Result#ignore(Object)} if the entity
     * was not found.</p>
     *
     * @param request the request containing the parameter passed from {@link #forward(String, Object, Object)}
     * @return the result containing the data that will be passed to {@link #onForwardSucceeded(Object, Object)}
     */
    protected abstract Result<R> onForward(Request request);

    /**
     * Subclasses must implement this method, that runs on the <em>requesting node</em>,
     * to complete the functionality after the action has been successfully run on the <em>owner node</em>.
     *
     * @param result  the result of the action
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
    public String toString() {
        return String.format("%s[%s]@%s", getClass().getSimpleName(), getName(), getOort().getURL());
    }

    /**
     * Encapsulates a forwarded request.
     *
     * @see #onForward(Request)
     * @see Result
     */
    public static class Request {
        private final String localOortURL;
        private final Object data;
        private final String oortURL;

        private Request(String localOortURL, Object data, String oortURL) {
            this.localOortURL = localOortURL;
            this.data = data;
            this.oortURL = oortURL;
        }

        /**
         * @return the request data
         */
        public Object getData() {
            return data;
        }

        /**
         * @return the request data as a {@code Map&lt;String, Object&gt;}
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> getDataAsMap() {
            return (Map<String, Object>)getData();
        }

        /**
         * @return the Oort URL of the <em>requesting node</em>
         */
        public String getOortURL() {
            return oortURL;
        }

        /**
         * @return whether the request is local to the current Oort node
         */
        public boolean isLocal() {
            return localOortURL.equals(getOortURL());
        }
    }

    /**
     * <p>Encapsulates the result of a forwarded request returned by {@link #onForward(Request)}.</p>
     * <p>Applications must use methods {@link #success(Object)}, {@link #failure(Object)} or {@link #ignore(Object)}
     * to signal to the implementation the result of the forwarded request.</p>
     * <p>{@link OortService} may forward a request action for an entity to the owner node, but before the request
     * arrives to the owner node, the entity may be removed from the owner node.
     * When the owner node receives the request for the entity, the entity is not available anymore.
     * Similarly, a request that is broadcast to all nodes arrives to nodes that do not own the entity.
     * The node that receives the request must return a {@link Result} using the following rules:</p>
     * <ul>
     * <li>If the node owns the entity, use {@link #success(Object)} or {@link #failure(Object)} - never use
     * {@link #ignore(Object)}</li>
     * <li>If the node does not own the entity (it never was the owner, or it was the owner but it is not anymore),
     * then use {@link #ignore(Object)}</li>
     * </ul>
     *
     * @param <U> the result type
     * @see Request
     */
    public static class Result<U> {
        private final Boolean result;
        private final Object data;

        private Result(Boolean result, Object data) {
            this.result = result;
            this.data = data;
        }

        /**
         * Returns a successful {@link Result} containing the given result object.
         *
         * @param result the result object
         * @param <S>    the type of the result object
         * @return a new {@link Result} instance wrapping the result object
         */
        public static <S> Result<S> success(S result) {
            return new Result<>(true, result);
        }

        /**
         * Returns a failed {@link Result} containing the given failure object.
         *
         * @param failure the failure object
         * @param <S>     the type of the result
         * @return a new {@link Result} instance wrapping the failure object
         */
        public static <S> Result<S> failure(Object failure) {
            return new Result<>(false, failure);
        }

        /**
         * Returns an ignored {@link Result} containing the given data object
         *
         * @param data the data object
         * @param <S>  the type of the result
         * @return a new {@link Result} instance wrapping the data object
         */
        public static <S> Result<S> ignore(Object data) {
            return new Result<>(null, data);
        }

        private boolean succeeded() {
            return result != null && result;
        }

        private boolean failed() {
            return result != null && !result;
        }

        @Override
        public String toString() {
            return String.format("%s[%s] %s",
                    getClass().getSimpleName(),
                    result == null ? "ignored" : result ? "success" : "failure",
                    data);
        }
    }

    /**
     * <p>Utility context that stores the {@link ServerSession} and the {@link ServerMessage}.</p>
     * <p>CometD services that extend {@link OortService} may register themselves as listeners
     * for messages sent by remote clients. In such case, this class will come handy in this way:</p>
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
    public static class ServerContext {
        private final ServerSession session;
        private final ServerMessage message;

        public ServerContext(ServerSession session, ServerMessage message) {
            this.session = session;
            this.message = message;
        }

        public ServerSession getServerSession() {
            return session;
        }

        public ServerMessage getServerMessage() {
            return message;
        }
    }

    private class TimeoutTask implements Runnable {
        private final long contextId;

        private TimeoutTask(long contextId) {
            this.contextId = contextId;
        }

        @Override
        public void run() {
            Map<String, Object> data = new HashMap<>(3);
            data.put(ID_FIELD, contextId);
            data.put(RESULT_FIELD, false);
            data.put(DATA_FIELD, new TimeoutException());
            onResultMessage(data);
        }
    }
}
