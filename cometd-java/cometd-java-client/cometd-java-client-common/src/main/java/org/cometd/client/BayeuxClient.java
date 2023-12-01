/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.client;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.AsyncFoldLeft;
import org.cometd.common.TransportException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>BayeuxClient is the implementation of a client for the Bayeux protocol.</p>
 * <p> A BayeuxClient can receive/publish messages from/to a Bayeux server, and
 * it is the counterpart in Java of the JavaScript library used in browsers (and as such
 * it is ideal for Swing applications, load testing tools, etc.).</p>
 * <p>A BayeuxClient handshakes with a Bayeux server
 * and then subscribes {@link ClientSessionChannel.MessageListener} to channels in order
 * to receive messages, and may also publish messages to the Bayeux server.</p>
 * <p>BayeuxClient relies on pluggable transports for communication with the Bayeux
 * server.</p>
 * <p>When the communication with the server is finished, the BayeuxClient can be
 * disconnected from the Bayeux server.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * // Setup Jetty's HttpClient.
 * HttpClient httpClient = new HttpClient();
 * httpClient.start();
 *
 * // Handshake
 * String uri = "http://localhost:8080/cometd";
 * BayeuxClient client = new BayeuxClient(uri, new JettyHttpClientTransport(null, httpClient));
 * client.handshake();
 * client.waitFor(1000, BayeuxClient.State.CONNECTED);
 *
 * // Subscription to channels
 * ClientSessionChannel channel = client.getChannel("/foo");
 * channel.subscribe((channel, message) -> {
 *     // Handle the message
 * });
 *
 * // Publishing to channels
 * Map<String, Object> data = new HashMap<>();
 * data.put("bar", "baz");
 * channel.publish(data);
 *
 * // Disconnecting
 * client.disconnect();
 * client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
 * }</pre>
 */
public class BayeuxClient extends AbstractClientSession implements Bayeux {
    public static final String BACKOFF_INCREMENT_OPTION = "backoffIncrement";
    public static final String MAX_BACKOFF_OPTION = "maxBackoff";
    public static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = LoggerFactory.getLogger(getClass().getName() + "." + Integer.toHexString(System.identityHashCode(this)));
    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final CopyOnWriteArrayList<TransportListener> transportListeners = new CopyOnWriteArrayList<>();
    private final TransportRegistry transportRegistry = new TransportRegistry();
    private final Map<String, Object> options = new ConcurrentHashMap<>();
    private final List<Message.Mutable> messageQueue = new ArrayList<>(32);
    private final HttpCookieStore cookieStore = new HttpCookieStore.Default();
    private final TransportListener messageListener = new MessageTransportListener();
    private final SessionState sessionState = new SessionState();
    private final String url;
    private ScheduledExecutorService scheduler;
    private boolean ownScheduler;
    private BackOffStrategy backOffStrategy = new BackOffStrategy.Linear();

    /**
     * <p>Creates a BayeuxClient that will connect to the Bayeux server at the given URL
     * and with the given transport(s).</p>
     * <p>This constructor allocates a new {@link ScheduledExecutorService scheduler}; it is recommended that
     * when creating a large number of BayeuxClients a shared scheduler is used.</p>
     *
     * @param url        the Bayeux server URL to connect to
     * @param transport  the default (mandatory) transport to use
     * @param transports additional optional transports to use in case the default transport cannot be used
     * @see #BayeuxClient(String, ScheduledExecutorService, ClientTransport, ClientTransport...)
     */
    public BayeuxClient(String url, ClientTransport transport, ClientTransport... transports) {
        this(url, null, transport, transports);
    }

    /**
     * <p>Creates a BayeuxClient that will connect to the Bayeux server at the given URL,
     * with the given scheduler and with the given transport(s).</p>
     *
     * @param url        the Bayeux server URL to connect to
     * @param scheduler  the scheduler to use for scheduling timed operations
     * @param transport  the default (mandatory) transport to use
     * @param transports additional optional transports to use in case the default transport cannot be used
     */
    public BayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport transport, ClientTransport... transports) {
        this.url = Objects.requireNonNull(url);
        this.scheduler = scheduler;
        Objects.requireNonNull(transport);
        transportRegistry.add(transport);
        for (ClientTransport t : transports) {
            transportRegistry.add(t);
        }

        for (String transportName : transportRegistry.getKnownTransports()) {
            ClientTransport clientTransport = transportRegistry.getTransport(transportName);
            clientTransport.setOption(ClientTransport.URL_OPTION, url);
            if (clientTransport instanceof MessageClientTransport) {
                ((MessageClientTransport)clientTransport).setMessageTransportListener(messageListener);
            }
            if (clientTransport instanceof HttpClientTransport httpTransport) {
                httpTransport.setHttpCookieStore(cookieStore);
            }
        }
    }

    /**
     * @return the URL passed when constructing this instance
     */
    public String getURL() {
        return url;
    }

    public BackOffStrategy getBackOffStrategy() {
        return backOffStrategy;
    }

    public void setBackOffStrategy(BackOffStrategy backOffStrategy) {
        this.backOffStrategy = backOffStrategy;
    }

    public HttpCookieStore getHttpCookieStore() {
        return cookieStore;
    }

    /**
     * <p>Retrieves the first cookie with the given name, if available.</p>
     * <p>Note that currently only HTTP transports support cookies.</p>
     *
     * @param name the cookie name
     * @return the cookie, or null if no such cookie is found
     * @see #putCookie(HttpCookie)
     */
    public HttpCookie getCookie(String name) {
        for (HttpCookie cookie : getHttpCookieStore().match(URI.create(getURL()))) {
            if (name.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    public void putCookie(HttpCookie cookie) {
        URI uri = URI.create(getURL());
        getHttpCookieStore().add(uri, cookie);
    }

    @Override
    public String getId() {
        return sessionState.getSessionId();
    }

    @Override
    public boolean isHandshook() {
        State state = getState();
        return state == State.HANDSHAKEN || state == State.CONNECTING || state == State.CONNECTED || state == State.UNCONNECTED;
    }

    @Override
    public boolean isConnected() {
        return getState() == State.CONNECTED;
    }

    /**
     * @return whether this BayeuxClient is terminating or disconnected
     */
    public boolean isDisconnected() {
        State state = getState();
        return state == State.TERMINATING || state == State.DISCONNECTED;
    }

    /**
     * @return the current state of this BayeuxClient
     */
    protected State getState() {
        return sessionState.getState();
    }

    /**
     * @param listener the {@link TransportListener} to add
     */
    public void addTransportListener(TransportListener listener) {
        transportListeners.add(listener);
    }

    /**
     * @param listener the {@link TransportListener} to remove
     */
    public void removeTransportListener(TransportListener listener) {
        transportListeners.remove(listener);
    }

    private void notifyTransportSending(List<? extends Message> messages) {
        for (TransportListener listener : transportListeners) {
            try {
                listener.onSending(messages);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyTransportMessages(List<Message.Mutable> messages) {
        for (TransportListener listener : transportListeners) {
            try {
                listener.onMessages(messages);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyTransportFailure(Throwable failure, List<? extends Message> messages) {
        for (TransportListener listener : transportListeners) {
            try {
                listener.onFailure(failure, messages);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyTransportTimeout(List<? extends Message> messages, Promise<Long> promise) {
        AsyncFoldLeft.run(transportListeners, 0L, (result, listener, loop) -> {
            try {
                listener.onTimeout(messages, Promise.from(r -> {
                    if (r > 0) {
                        loop.leave(r);
                    } else {
                        loop.proceed(0L);
                    }
                }, loop::fail));
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
                loop.fail(x);
            }
        }, promise);
    }

    /**
     * @param callback the message listener to notify of the handshake result
     */
    public void handshake(MessageListener callback) {
        handshake(null, callback);
    }

    @Override
    public void handshake(Map<String, Object> fields, MessageListener callback) {
        State state = getState();
        if (state == State.DISCONNECTED) {
            sessionState.submit(() -> sessionState.handshaking(fields, callback));
        } else {
            throw new IllegalStateException("Invalid state " + state);
        }
    }

    /**
     * <p>Performs the handshake and waits at most the given time for the handshake to complete.</p>
     * <p>When this method returns, the handshake may have failed (for example because the Bayeux
     * server denied it), so it is important to check the return value to know whether the handshake
     * completed or not.</p>
     *
     * @param waitMs the time to wait for the handshake to complete
     * @return the state of this BayeuxClient
     * @see #handshake(Map, long)
     */
    public State handshake(long waitMs) {
        return handshake(null, waitMs);
    }

    /**
     * <p>Performs the handshake with the given template and waits at most the given time for the handshake to complete.</p>
     * <p>When this method returns, the handshake may have failed (for example because the Bayeux
     * server denied it), so it is important to check the return value to know whether the handshake
     * completed or not.</p>
     *
     * @param template the template object to be merged with the handshake message
     * @param waitMs   the time to wait for the handshake to complete
     * @return the state of this BayeuxClient
     * @see #handshake(long)
     */
    public State handshake(Map<String, Object> template, long waitMs) {
        handshake(template);
        waitFor(waitMs, State.CONNECTING, State.CONNECTED, State.DISCONNECTED);
        return getState();
    }

    protected void sendHandshake() {
        List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
        List<String> transportNames = new ArrayList<>(transports.size());
        for (ClientTransport transport : transports) {
            transportNames.add(transport.getName());
        }

        Message.Mutable message = newMessage();
        Map<String, Object> handshakeFields = sessionState.getHandshakeFields();
        if (handshakeFields != null) {
            message.putAll(handshakeFields);
        }

        String messageId = newMessageId();
        message.setId(messageId);
        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transportNames);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        registerCallback(messageId, sessionState.getHandshakeCallback());

        if (logger.isDebugEnabled()) {
            logger.debug("Handshaking on transport {}: {}", getTransport(), message);
        }

        List<Message.Mutable> messages = List.of(message);
        sendMessages(messages, Promise.complete((r, x) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("{} handshake {}", x == null ? "Sent" : "Failed", message);
            }
        }));
    }

    /**
     * <p>Waits for this BayeuxClient to reach the given state(s) within the given time.</p>
     *
     * @param waitMs the time to wait to reach the given state(s)
     * @param state  the state to reach
     * @param states additional states to reach in alternative
     * @return true if one of the state(s) has been reached within the given time, false otherwise
     */
    public boolean waitFor(long waitMs, State state, State... states) {
        List<State> waitForStates = new ArrayList<>();
        waitForStates.add(state);
        waitForStates.addAll(List.of(states));

        try (AutoLock ignored = lock.lock()) {
            final long startNs = NanoTime.now();
            long elapsedMs = 0L;
            while (true) {
                // This check is needed to avoid that we return from waitFor() too early,
                // when the state has been set, but its effects (like notifying listeners)
                // are not completed yet (issue #212).
                // Transient states (like CONNECTING or DISCONNECTING) may "miss" the
                // wake up in this way:
                // * T1 goes in wait - releases lock
                // * T2 finishes update to CONNECTING - notifies lock
                // * T3 starts a state update to CONNECTED - releases lock
                // * T1 wakes up, takes lock, but sees update in progress, waits - releases lock
                // * T3 finishes update to CONNECTED - notifies lock
                // * T1 wakes up, takes lock, sees status == CONNECTED - CONNECTING has been "missed"
                // To avoid this, we use State.implies()
                if (sessionState.isIdle()) {
                    State currentState = getState();
                    for (State s : waitForStates) {
                        if (currentState.implies(s)) {
                            return true;
                        }
                    }
                }

                long delay = waitMs - elapsedMs;
                if (logger.isDebugEnabled()) {
                    logger.debug("Waiting {}ms for {}", delay, waitForStates);
                }

                if (sessionState.await(delay)) {
                    break;
                }
                elapsedMs = NanoTime.millisSince(startNs);

                if (logger.isDebugEnabled()) {
                    logger.debug("Waited {}/{}ms for {}, state is {}", elapsedMs, waitMs, waitForStates, sessionState.getState());
                }

                if (waitMs - elapsedMs < 0) {
                    break;
                }
            }
            return false;
        }
    }

    protected void sendConnect() {
        ClientTransport transport = getTransport();
        if (transport == null) {
            return;
        }

        Message.Mutable message = newMessage();
        message.setId(newMessageId());
        message.setChannel(Channel.META_CONNECT);
        message.put(Message.CONNECTION_TYPE_FIELD, transport.getName());

        State state = getState();
        if (state == State.CONNECTING || state == State.UNCONNECTED) {
            // First connect after handshake or after failure, add advice.
            message.getAdvice(true).put("timeout", 0);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Connecting, transport {}", transport);
        }

        List<Message.Mutable> messages = List.of(message);
        sendMessages(messages, Promise.complete((r, x) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("{} connect {}", x == null ? "Sent" : "Failed", message);
            }
        }));
    }

    @Override
    protected ChannelId newChannelId(String channelId) {
        // Save some parsing by checking if there is already one
        AbstractSessionChannel channel = getChannels().get(channelId);
        return channel == null ? new ChannelId(channelId) : channel.getChannelId();
    }

    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId) {
        return new BayeuxClientChannel(channelId);
    }

    @Override
    protected void sendBatch() {
        if (canSend()) {
            List<Message.Mutable> messages = takeMessages();
            if (!messages.isEmpty()) {
                sendMessages(messages, Promise.complete((r, x) -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{} batch {}", x == null ? "Sent" : "Failed", messages);
                    }
                }));
            }
        }
    }

    protected void sendMessages(List<Message.Mutable> messages, Promise<Boolean> promise) {
        AsyncFoldLeft.run(messages, new ArrayList<Message.Mutable>(messages.size()), (toSend, message, loop) -> {
            String messageId = message.getId();
            message.setClientId(sessionState.sessionId);
            extendOutgoing(message, Promise.from(extPass -> {
                // Extensions may have changed the messageId.
                message.setId(messageId);
                if (extPass) {
                    toSend.add(message);
                }
                loop.proceed(toSend);
            }, loop::fail));
        }, Promise.from(toSend -> {
            List<Message.Mutable> deleted = List.of();
            if (toSend.size() != messages.size()) {
                deleted = new ArrayList<>(messages);
                deleted.removeAll(toSend);
            }

            // Fail the deleted messages first, to avoid a
            // race with the replies of the sent messages.
            AsyncFoldLeft.run(deleted, null, (result, message, loop) -> {
                Message.Mutable failed = newReply(message);
                failed.setSuccessful(false);
                failed.put(Message.ERROR_FIELD, "404::message_deleted");
                receive(failed, Promise.from(loop::proceed, loop::fail));
            }, Promise.from(r -> {
                // Send the messages.
                if (toSend.isEmpty()) {
                    promise.succeed(false);
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Sending messages {}", toSend);
                    }
                    promise.succeed(sessionState.send(messageListener, toSend));
                }
            }, promise::fail));
        }, promise::fail));
    }

    private List<Message.Mutable> takeMessages() {
        // Multiple threads can call this method concurrently (for example
        // a batched publish() is executed exactly when a message arrives
        // and a listener also performs a batched publish() in response to
        // the message).
        // The queue must be drained atomically, otherwise we risk that the
        // same message is drained twice.

        List<Message.Mutable> messages;
        try (AutoLock ignored = lock.lock()) {
            messages = new ArrayList<>(messageQueue);
            messageQueue.clear();
        }
        return messages;
    }

    @Override
    public void disconnect(ClientSession.MessageListener callback) {
        sessionState.submit(() -> sessionState.disconnecting(callback));
    }

    /**
     * <p>Performs a {@link #disconnect() disconnect} and uses the given {@code timeout}
     * to wait for the disconnect to complete.</p>
     * <p>When a disconnect is sent to the server, the server also wakes up the long
     * poll that may be outstanding, so that a connect reply message may arrive to
     * the client later than the disconnect reply message.</p>
     * <p>This method waits for the given {@code timeout} for the disconnect reply, but also
     * waits the same timeout for the last connect reply; in the worst case the
     * maximum time waited will therefore be twice the given {@code timeout} parameter.</p>
     * <p>This method returns true if the disconnect reply message arrived within the
     * given {@code timeout} parameter, no matter if the connect reply message arrived or not.</p>
     *
     * @param timeout the timeout to wait for the disconnect to complete
     * @return true if the disconnect completed within the given timeout
     */
    public boolean disconnect(long timeout) {
        if (isDisconnected()) {
            return true;
        }

        CountDownLatch latch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener lastConnectListener = (channel, message) -> {
            Map<String, Object> advice = message.getAdvice();
            if (!message.isSuccessful() ||
                    advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD))) {
                latch.countDown();
            }
        };
        getChannel(Channel.META_CONNECT).addListener(lastConnectListener);

        disconnect();
        boolean disconnected = waitFor(timeout, BayeuxClient.State.DISCONNECTED);

        // There is a possibility that we are in the window where the server
        // has returned the long poll and the client has not issued it again,
        // so wait for the timeout, but do not complain if the latch does not trigger.
        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }

        getChannel(Channel.META_CONNECT).removeListener(lastConnectListener);

        // Force termination.
        sessionState.submit(sessionState::terminating);

        return disconnected;
    }

    /**
     * <p>Interrupts abruptly the communication with the Bayeux server.</p>
     * <p>This method may be useful to simulate network failures.</p>
     *
     * @see #disconnect()
     */
    public void abort() {
        abort(new IOException("Abort"));
    }

    private void abort(Throwable failure) {
        sessionState.submit(() -> {
            if (sessionState.update(State.TERMINATING)) {
                sessionState.terminate(failure);
            }
        });
    }

    private void processMessages(List<Message.Mutable> messages) {
        for (Message.Mutable message : messages) {
            if (logger.isDebugEnabled()) {
                logger.debug("Processing {}", message);
            }

            String channel = message.getChannel();
            if (Channel.META_HANDSHAKE.equals(channel)) {
                processHandshake(message);
            } else if (Channel.META_CONNECT.equals(channel)) {
                processConnect(message);
            } else if (Channel.META_DISCONNECT.equals(channel)) {
                processDisconnect(message);
            } else {
                processMessage(message);
            }
        }
    }

    protected void messagesFailure(Throwable cause, List<? extends Message> messages) {
        for (Message message : messages) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failing {}", message);
            }

            Message.Mutable failed = newReply(message);
            failed.setSuccessful(false);
            Map<String, Object> failure = new HashMap<>();
            failed.put("failure", failure);
            failure.put("message", message);
            if (cause != null) {
                failure.put("exception", cause);
            }
            if (cause instanceof TransportException) {
                Map<String, Object> fields = ((TransportException)cause).getFields();
                if (fields != null) {
                    failure.putAll(fields);
                }
            }
            ClientTransport transport = getTransport();
            if (transport != null) {
                failure.put(Message.CONNECTION_TYPE_FIELD, transport.getName());
            }

            String channel = message.getChannel();
            if (Channel.META_HANDSHAKE.equals(channel)) {
                handshakeFailure(failed, cause);
            } else if (Channel.META_CONNECT.equals(channel)) {
                connectFailure(failed, cause);
            } else if (Channel.META_DISCONNECT.equals(channel)) {
                disconnectFailure(failed, cause);
            } else {
                messageFailure(failed, cause);
            }
        }
    }

    private Message.Mutable newReply(Message message) {
        Message.Mutable reply = newMessage();
        reply.setId(message.getId());
        reply.setChannel(message.getChannel());
        if (message.containsKey(Message.SUBSCRIPTION_FIELD)) {
            reply.put(Message.SUBSCRIPTION_FIELD, message.get(Message.SUBSCRIPTION_FIELD));
        }
        return reply;
    }

    protected void processHandshake(Message.Mutable handshake) {
        if (handshake.isSuccessful()) {
            ClientTransport oldTransport = getTransport();

            Object field = handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD);
            Object[] serverTransports = field instanceof List ? ((List<?>)field).toArray() : (Object[])field;
            List<ClientTransport> negotiatedTransports = transportRegistry.negotiate(serverTransports, BAYEUX_VERSION);
            if (negotiatedTransports.isEmpty()) {
                ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
                failureInfo.transport = null;
                failureInfo.cause = null;
                failureInfo.error = String.format("405:c%s,s%s:no_transport", getAllowedTransports(), Arrays.toString(serverTransports));
                failureInfo.action = Message.RECONNECT_NONE_VALUE;
                handshake.setSuccessful(false);
                handshake.put(Message.ERROR_FIELD, failureInfo.error);
                failHandshake(handshake, failureInfo);
            } else {
                Number messagesField = (Number)handshake.get("x-messages");
                int messages = messagesField == null ? 0 : messagesField.intValue();

                ClientTransport newTransport = negotiatedTransports.get(0);
                if (newTransport != oldTransport) {
                    prepareTransport(oldTransport, newTransport);
                }

                sessionState.submit(() -> sessionState.handshaken(newTransport, handshake, messages));
            }
        } else {
            ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
            failureInfo.transport = getTransport();
            failureInfo.cause = null;
            failureInfo.error = null;
            failureInfo.action = sessionState.getAdviceAction(handshake.getAdvice(), Message.RECONNECT_HANDSHAKE_VALUE);
            failHandshake(handshake, failureInfo);
        }
    }

    private void handshakeFailure(Message.Mutable handshake, Throwable failure) {
        ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
        failureInfo.transport = null;
        failureInfo.cause = failure;
        failureInfo.error = null;
        failureInfo.action = Message.RECONNECT_HANDSHAKE_VALUE;
        failHandshake(handshake, failureInfo);
    }

    private void failHandshake(Message.Mutable handshake, ClientTransport.FailureInfo failureInfo) {
        receive(handshake, Promise.from(r -> {
            // The listeners may have disconnected.
            if (isDisconnected()) {
                failureInfo.action = Message.RECONNECT_NONE_VALUE;
            }
            onTransportFailure(handshake, failureInfo, sessionState);
        }, x -> logger.info("Failure while receiving " + handshake, x)));
    }

    protected void processConnect(Message.Mutable connect) {
        if (sessionState.matchMetaConnect(connect)) {
            if (connect.isSuccessful()) {
                receive(connect, Promise.from(
                        r -> sessionState.submit(() -> sessionState.connected(connect)),
                        x -> logger.info("Failure while receiving " + connect, x)
                ));
            } else {
                ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
                failureInfo.transport = getTransport();
                failureInfo.cause = null;
                failureInfo.error = null;
                failureInfo.action = sessionState.getAdviceAction(connect.getAdvice(), Message.RECONNECT_RETRY_VALUE);
                failConnect(connect, failureInfo);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Mismatched /meta/connect reply: expected reply for {}, received {}", sessionState.getMetaConnect(), connect);
            }
        }
    }

    private void connectFailure(Message.Mutable connect, Throwable failure) {
        if (sessionState.matchMetaConnect(connect)) {
            ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
            failureInfo.transport = null;
            failureInfo.cause = failure;
            failureInfo.error = null;
            failureInfo.action = Message.RECONNECT_RETRY_VALUE;
            failConnect(connect, failureInfo);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Mismatched /meta/connect failure: expected {}, got {}", sessionState.getMetaConnect(), connect);
            }
        }
    }

    private void failConnect(Message.Mutable connect, ClientTransport.FailureInfo failureInfo) {
        receive(connect, Promise.from(r -> {
            // The listeners may have disconnected.
            if (isDisconnected()) {
                failureInfo.action = Message.RECONNECT_NONE_VALUE;
            }
            onTransportFailure(connect, failureInfo, sessionState);
        }, x -> logger.info("Failure while receiving " + connect, x)));
    }

    protected void processDisconnect(Message.Mutable disconnect) {
        if (disconnect.isSuccessful()) {
            receive(disconnect, Promise.complete((r, x) -> sessionState.submit(sessionState::terminating)));
        } else {
            disconnectFailure(disconnect, null);
        }
    }

    private void disconnectFailure(Message.Mutable disconnect, Throwable failure) {
        ClientTransport.FailureInfo failureInfo = new ClientTransport.FailureInfo();
        failureInfo.transport = getTransport();
        failureInfo.cause = failure;
        failureInfo.error = null;
        failureInfo.action = Message.RECONNECT_NONE_VALUE;
        failDisconnect(disconnect, failureInfo);
    }

    private void failDisconnect(Message.Mutable disconnect, ClientTransport.FailureInfo failureInfo) {
        receive(disconnect, Promise.complete((r, x) -> onTransportFailure(disconnect, failureInfo, sessionState)));
    }

    protected void processMessage(Message.Mutable message) {
        receive(message, Promise.from(r -> {
            if (getState() == State.HANDSHAKEN) {
                sessionState.submit(sessionState::afterHandshaken);
            }
        }, this::abort));
    }

    private void messageFailure(Message.Mutable message, Throwable failure) {
        failMessage(message);
    }

    private void failMessage(Message.Mutable message) {
        receive(message, Promise.from(r -> {
        }, x -> logger.info("Failure while receiving " + message, x)));
    }

    protected boolean scheduleHandshake(long interval, long backOff) {
        if (logger.isDebugEnabled()) {
            logger.debug("Scheduled handshake in {}+{} ms", interval, backOff);
        }
        return scheduleAction(this::sendHandshake, interval, backOff);
    }

    protected boolean scheduleConnect(long interval, long backOff) {
        if (logger.isDebugEnabled()) {
            logger.debug("Scheduled connect in {}+{} ms", interval, backOff);
        }
        return scheduleAction(this::sendConnect, interval, backOff);
    }

    private boolean scheduleAction(Runnable action, long interval, long backoff) {
        // Prevent NPE in case of concurrent disconnect
        ScheduledExecutorService scheduler = this.scheduler;
        if (scheduler != null) {
            try {
                scheduler.schedule(action, interval + backoff, TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException x) {
                // It has been shut down
                logger.trace("", x);
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Could not schedule action {} to scheduler {}", action, scheduler);
        }
        return false;
    }

    @Override
    public List<String> getAllowedTransports() {
        return transportRegistry.getAllowedTransports();
    }

    @Override
    public Set<String> getKnownTransportNames() {
        return transportRegistry.getKnownTransports();
    }

    @Override
    public ClientTransport getTransport(String transport) {
        return transportRegistry.getTransport(transport);
    }

    public ClientTransport getTransport() {
        return sessionState.getTransport();
    }

    protected void initialize() {
        BackOffStrategy backOffStrategy = getBackOffStrategy();
        if (backOffStrategy instanceof BackOffStrategy.Linear linear) {
            Number value = (Number)getOption(BACKOFF_INCREMENT_OPTION);
            long increment = linear.increment;
            if (value != null) {
                increment = value.longValue();
            }
            value = (Number)getOption(MAX_BACKOFF_OPTION);
            long maximum = linear.maximum;
            if (value != null) {
                maximum = value.longValue();
            }
            if (increment > 0 && increment != linear.increment || maximum != linear.maximum) {
                setBackOffStrategy(new BackOffStrategy.Linear(increment, maximum));
            }
        }

        if (scheduler == null) {
            scheduler = new Scheduler(1);
            ownScheduler = true;
        }
        setOption(ClientTransport.SCHEDULER_OPTION, scheduler);
    }

    protected void terminate(Throwable failure) {
        List<Message.Mutable> messages = takeMessages();
        messagesFailure(failure, messages);

        cookieStore.clear();

        if (ownScheduler) {
            scheduler.shutdown();
            scheduler = null;
            ownScheduler = false;
        }
    }

    @Override
    public Object getOption(String qualifiedName) {
        return options.get(qualifiedName);
    }

    @Override
    public void setOption(String qualifiedName, Object value) {
        options.put(qualifiedName, value);
        // Forward the option to the transports.
        for (String name : transportRegistry.getKnownTransports()) {
            ClientTransport transport = transportRegistry.getTransport(name);
            transport.setOption(qualifiedName, value);
        }
    }

    @Override
    public Set<String> getOptionNames() {
        return options.keySet();
    }

    /**
     * @return the options that configure with BayeuxClient.
     */
    public Map<String, Object> getOptions() {
        return Map.copyOf(options);
    }

    @Override
    protected void send(Message.Mutable message) {
        enqueueSend(message);
    }

    protected void enqueueSend(Message.Mutable message) {
        if (canSend()) {
            List<Message.Mutable> messages = List.of(message);
            sendMessages(messages, Promise.complete((r, x) -> {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} message {}", x == null ? "Sent" : "Failed", message);
                }
            }));
        } else {
            try (AutoLock ignored = lock.lock()) {
                messageQueue.add(message);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Enqueued message {} (batching: {})", message, isBatching());
            }
        }
    }

    private boolean canSend() {
        State state = getState();
        boolean handshaking = state == State.HANDSHAKING || state == State.REHANDSHAKING;
        return !isBatching() && !handshaking;
    }

    private void prepareTransport(ClientTransport oldTransport, ClientTransport newTransport) {
        if (oldTransport != null) {
            oldTransport.terminate();
        }
        newTransport.init();
    }

    protected void onTransportFailure(Message message, ClientTransport.FailureInfo failureInfo, ClientTransport.FailureHandler handler) {
        if (logger.isDebugEnabled()) {
            logger.debug("Transport failure: {} for {}", failureInfo, message);
        }

        if (Message.RECONNECT_NONE_VALUE.equals(failureInfo.action)) {
            if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                ClientTransport transport = getTransport();
                if (transport != null && failureInfo.transport == null) {
                    onTransportFailure(transport.getName(), null, failureInfo.cause);
                }
            }
        } else {
            failureInfo.delay = getBackOffStrategy().current();

            if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                // Invalid transport, try to negotiate a new one.
                if (failureInfo.transport == null) {
                    List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
                    if (transports.isEmpty()) {
                        onTransportFailure(getTransport().getName(), null, failureInfo.cause);
                        failureInfo.action = Message.RECONNECT_NONE_VALUE;
                    } else {
                        ClientTransport oldTransport = getTransport();
                        ClientTransport newTransport = transports.get(0);
                        if (newTransport != oldTransport) {
                            prepareTransport(oldTransport, newTransport);
                        }
                        onTransportFailure(oldTransport.getName(), newTransport.getName(), failureInfo.cause);
                        failureInfo.transport = newTransport;
                        failureInfo.action = Message.RECONNECT_HANDSHAKE_VALUE;
                    }
                }

                if (!Message.RECONNECT_NONE_VALUE.equals(failureInfo.action)) {
                    sessionState.increaseBackOff();
                }
            } else {
                sessionState.initUnconnectTime();

                if (Message.RECONNECT_RETRY_VALUE.equals(failureInfo.action)) {
                    failureInfo.delay = sessionState.increaseBackOff();
                    if (sessionState.nextConnectExceedsMaxInterval()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Switching to handshake retries");
                        }
                        failureInfo.action = Message.RECONNECT_HANDSHAKE_VALUE;
                    }
                }

                if (Message.RECONNECT_HANDSHAKE_VALUE.equals(failureInfo.action)) {
                    failureInfo.delay = 0;
                    sessionState.resetBackOff();
                }
            }
        }

        handler.handle(failureInfo);
    }

    protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure) {
    }

    @Override
    public String toString() {
        return String.format("%s@%x[%s][%s]", getClass().getSimpleName(), hashCode(), getId(), sessionState);
    }

    /**
     * The states that a BayeuxClient may assume.
     */
    public enum State {
        /**
         * State assumed after the handshake when the connection is broken
         */
        UNCONNECTED,
        /**
         * State assumed when the handshake is being sent
         */
        HANDSHAKING,
        /**
         * State assumed when a first handshake failed and the handshake is retried,
         * or when the Bayeux server requests a re-handshake
         */
        REHANDSHAKING,
        /**
         * State assumed when the handshake is received, but before connecting
         */
        HANDSHAKEN(HANDSHAKING, REHANDSHAKING),
        /**
         * State assumed when the connect is being sent for the first time
         */
        CONNECTING(HANDSHAKING, REHANDSHAKING, HANDSHAKEN),
        /**
         * State assumed when this BayeuxClient is connected to the Bayeux server
         */
        CONNECTED(HANDSHAKING, REHANDSHAKING, HANDSHAKEN, CONNECTING),
        /**
         * State assumed when the disconnect is being sent
         */
        DISCONNECTING,
        /**
         * State assumed when the disconnect is received but terminal actions must be performed
         */
        TERMINATING(DISCONNECTING),
        /**
         * State assumed before the handshake and when the disconnect is completed
         */
        DISCONNECTED(DISCONNECTING, TERMINATING);

        private final State[] implieds;

        private State(State... implieds) {
            this.implieds = implieds;
        }

        private boolean implies(State state) {
            if (state == this) {
                return true;
            }
            for (State implied : implieds) {
                if (state == implied) {
                    return true;
                }
            }
            return false;
        }

        private boolean isUpdateableTo(State newState) {
            return switch (this) {
                case DISCONNECTED -> newState == HANDSHAKING;
                case HANDSHAKING, REHANDSHAKING ->
                        EnumSet.of(REHANDSHAKING, HANDSHAKEN, DISCONNECTING, TERMINATING).contains(newState);
                case HANDSHAKEN -> EnumSet.of(CONNECTING, DISCONNECTING, TERMINATING).contains(newState);
                case CONNECTING, CONNECTED, UNCONNECTED ->
                        EnumSet.of(REHANDSHAKING, CONNECTED, UNCONNECTED, DISCONNECTING, TERMINATING).contains(newState);
                case DISCONNECTING -> newState == TERMINATING;
                case TERMINATING -> newState == DISCONNECTED;
            };
        }
    }

    /**
     * <p>A strategy to controls wait times of the retry attempts in case of heartbeat failures.</p>
     * <p>Method {@link #next()} is invoked when a retry attempt has failed and returns the wait time
     * before the next retry attempt.</p>
     */
    public interface BackOffStrategy {
        /**
         * @return the current wait time
         * @see #next()
         * @see #reset()
         */
        public long current();

        /**
         * @return the next wait time
         * @see #current()
         */
        public long next();

        /**
         * Resets the wait time.
         */
        public void reset();

        /**
         * <p>A back off strategy that always returns the same wait time.</p>
         */
        public static class Constant implements BackOffStrategy {
            private final long delay;
            private volatile long backOff;

            public Constant(long delay) {
                this.delay = delay;
            }

            @Override
            public long current() {
                return backOff;
            }

            @Override
            public long next() {
                return backOff = delay;
            }

            @Override
            public void reset() {
                backOff = 0;
            }
        }

        /**
         * <p>A strategy that increases the wait time linearly up to a maximum.</p>
         */
        public static class Linear implements BackOffStrategy {
            private final AutoLock lock = new AutoLock();
            private final long increment;
            private final long maximum;
            private long backOff;

            public Linear() {
                this(1000, 30000);
            }

            public Linear(long increment, long maximum) {
                this.increment = increment;
                this.maximum = maximum;
            }

            @Override
            public long current() {
                try (AutoLock ignored = lock.lock()) {
                    return backOff;
                }
            }

            @Override
            public long next() {
                try (AutoLock ignored = lock.lock()) {
                    long newBackOff = backOff + increment;
                    if (maximum > 0 && newBackOff > maximum) {
                        newBackOff = maximum;
                    }
                    return backOff = newBackOff;
                }
            }

            @Override
            public void reset() {
                try (AutoLock ignored = lock.lock()) {
                    backOff = 0;
                }
            }
        }
    }

    private class MessageTransportListener implements TransportListener {
        @Override
        public void onSending(List<? extends Message> messages) {
            notifyTransportSending(messages);
        }

        @Override
        public void onMessages(List<Message.Mutable> messages) {
            notifyTransportMessages(messages);
            processMessages(messages);
        }

        @Override
        public void onFailure(Throwable failure, List<? extends Message> messages) {
            notifyTransportFailure(failure, messages);
            messagesFailure(failure, messages);
        }

        @Override
        public void onTimeout(List<? extends Message> messages, Promise<Long> promise) {
            notifyTransportTimeout(messages, promise);
        }
    }

    /**
     * <p>A channel scoped to this BayeuxClient.</p>
     */
    protected class BayeuxClientChannel extends AbstractSessionChannel {
        protected BayeuxClientChannel(ChannelId channelId) {
            super(channelId);
        }

        @Override
        public ClientSession getSession() {
            throwIfReleased();
            return BayeuxClient.this;
        }

        @Override
        protected void nonFirstSubscribe(Message.Mutable message, MessageListener listener, ClientSession.MessageListener callback) {
            // Keep the semantic that the callback is notified asynchronously.
            scheduleAction(() -> super.nonFirstSubscribe(message, listener, callback), 0, 0);
        }

        @Override
        protected void nonLastUnSubscribe(Message.Mutable message, MessageListener listener, ClientSession.MessageListener callback) {
            // Keep the semantic that the callback is notified asynchronously.
            scheduleAction(() -> super.nonLastUnSubscribe(message, listener, callback), 0, 0);
        }
    }

    private class SessionState implements ClientTransport.FailureHandler {
        private final Queue<Runnable> actions = new ArrayDeque<>();
        private State state = State.DISCONNECTED;
        private ClientTransport transport;
        private Map<String, Object> handshakeFields;
        private ClientSession.MessageListener handshakeCallback;
        private Map<String, Object> advice;
        private String sessionId;
        private long unconnectTime;
        private boolean active;
        private int handshakeMessages;
        private Message metaConnect;

        private void reset() {
            actions.clear();
            state = State.DISCONNECTED;
            transport = null;
            handshakeFields = null;
            handshakeCallback = null;
            advice = null;
            sessionId = null;
            resetBackOff();
            unconnectTime = 0;
            active = false;
            handshakeMessages = 0;
            metaConnect = null;
        }

        private State getState() {
            try (AutoLock ignored = lock.lock()) {
                return state;
            }
        }

        private ClientTransport getTransport() {
            try (AutoLock ignored = lock.lock()) {
                return transport;
            }
        }

        private Map<String, Object> getHandshakeFields() {
            try (AutoLock ignored = lock.lock()) {
                return handshakeFields;
            }
        }

        private ClientSession.MessageListener getHandshakeCallback() {
            try (AutoLock ignored = lock.lock()) {
                return handshakeCallback;
            }
        }

        private Map<String, Object> getAdvice() {
            try (AutoLock ignored = lock.lock()) {
                return advice;
            }
        }

        private String getSessionId() {
            try (AutoLock ignored = lock.lock()) {
                return sessionId;
            }
        }

        private long getUnconnectTime() {
            try (AutoLock ignored = lock.lock()) {
                if (unconnectTime == 0) {
                    return 0;
                }
                return NanoTime.millisSince(unconnectTime);
            }
        }

        private long getTimeout() {
            return getAdviceLong(Message.TIMEOUT_FIELD);
        }

        private long getInterval() {
            return getAdviceLong(Message.INTERVAL_FIELD);
        }

        private long getMaxInterval() {
            return getAdviceLong(Message.MAX_INTERVAL_FIELD);
        }

        private String getAdviceAction(Map<String, Object> advice, String defaultAction) {
            String result = null;
            if (advice == null) {
                advice = getAdvice();
            }
            if (advice != null) {
                result = (String)advice.get(Message.RECONNECT_FIELD);
            }
            return result == null ? defaultAction : result;
        }

        private long getAdviceLong(String field) {
            try (AutoLock ignored = lock.lock()) {
                long result = 0;
                if (advice != null && advice.containsKey(field)) {
                    result = ((Number)advice.get(field)).longValue();
                }
                return result;
            }
        }

        private long increaseBackOff() {
            try (AutoLock ignored = lock.lock()) {
                return getBackOffStrategy().next();
            }
        }

        private void resetBackOff() {
            try (AutoLock ignored = lock.lock()) {
                getBackOffStrategy().reset();
            }
        }

        private void handshaking(Map<String, Object> handshakeFields, ClientSession.MessageListener handshakeCallback) {
            if (update(State.HANDSHAKING)) {
                initialize();

                List<String> allowedTransports = getAllowedTransports();
                // Pick the first transport for the handshake, it will renegotiate if not right
                ClientTransport transport = transportRegistry.negotiate(allowedTransports.toArray(), BAYEUX_VERSION).get(0);
                prepareTransport(null, transport);
                if (logger.isDebugEnabled()) {
                    logger.debug("Using initial transport {} from {}", transport.getName(), allowedTransports);
                }

                try (AutoLock ignored = lock.lock()) {
                    this.transport = transport;
                    this.handshakeFields = handshakeFields;
                    this.handshakeCallback = handshakeCallback;
                }

                resetSubscriptions();
                sendHandshake();
            }
        }

        private void rehandshaking(long backOff) {
            State oldState;
            boolean result;
            try (AutoLock ignored = lock.lock()) {
                oldState = state;
                result = update(State.REHANDSHAKING);
            }
            if (result) {
                if (oldState != State.HANDSHAKING) {
                    resetSubscriptions();
                }
                scheduleHandshake(getInterval(), backOff);
            }
        }

        private void handshaken(ClientTransport transport, Message.Mutable handshake, int messages) {
            boolean updated;
            try (AutoLock ignored = lock.lock()) {
                updated = update(State.HANDSHAKEN);
                if (updated) {
                    this.transport = transport;
                    this.advice = handshake.getAdvice();
                    this.sessionId = handshake.getClientId();
                    this.handshakeMessages = messages;
                    resetBackOff();
                }
            }
            if (updated) {
                receive(handshake, Promise.from(r -> {
                    sendBatch();
                    if (messages == 0) {
                        connecting();
                    }
                }, x -> logger.info("Failure while receiving " + handshake, x)));
            }
        }

        private void afterHandshaken() {
            boolean connect = false;
            try (AutoLock ignored = lock.lock()) {
                if (getState() == State.HANDSHAKEN) {
                    if (handshakeMessages > 0) {
                        --handshakeMessages;
                    }
                    connect = handshakeMessages == 0;
                }
            }
            if (connect) {
                connecting();
            }
        }

        private void connecting() {
            if (update(State.CONNECTING)) {
                scheduleConnect(getInterval(), 0);
            }
        }

        private void connected(Message connect) {
            boolean updated;
            try (AutoLock ignored = lock.lock()) {
                updated = update(State.CONNECTED);
                if (updated) {
                    resetBackOff();
                    this.unconnectTime = 0;
                    Map<String, Object> advice = connect.getAdvice();
                    if (advice != null) {
                        this.advice = advice;
                    }
                }
            }
            if (updated) {
                scheduleConnect(getInterval(), 0);
            }
        }

        private void unconnected(long backOff) {
            if (update(State.UNCONNECTED)) {
                scheduleConnect(getInterval(), backOff);
            }
        }

        private boolean nextConnectExceedsMaxInterval() {
            try (AutoLock ignored = lock.lock()) {
                long maxInterval = getMaxInterval();
                if (maxInterval > 0) {
                    long expiration = getTimeout() + getInterval() + maxInterval;
                    return getUnconnectTime() + getBackOffStrategy().current() > expiration;
                }
                return false;
            }
        }

        private void disconnecting(ClientSession.MessageListener callback) {
            if (update(State.DISCONNECTING)) {
                Message.Mutable message = newMessage();
                String messageId = newMessageId();
                message.setId(messageId);
                message.setChannel(Channel.META_DISCONNECT);

                registerCallback(messageId, callback);

                List<Message.Mutable> messages = List.of(message);
                sendMessages(messages, Promise.complete((r, x) -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{} disconnect {}", x == null ? "Sent" : "Failed", message);
                    }
                }));
            }
        }

        private void terminating() {
            if (update(State.TERMINATING)) {
                terminate(null);
            }
        }

        private boolean update(State newState) {
            try (AutoLock ignored = lock.lock()) {
                State oldState = state;
                boolean result = state.isUpdateableTo(newState);
                if (result) {
                    state = newState;
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("State {}updated: {} -> {}", result ? "" : "not ", oldState, newState);
                }
                return result;
            }
        }

        private void terminate(Throwable failure) {
            if (failure != null) {
                transport.abort(failure);
            } else {
                transport.terminate();
            }

            BayeuxClient.this.terminate(failure);

            try (AutoLock ignored = lock.lock()) {
                update(State.DISCONNECTED);
                reset();
            }
        }

        private void submit(Runnable action) {
            boolean empty;
            try (AutoLock ignored = lock.lock()) {
                empty = actions.isEmpty();
                actions.offer(action);
            }
            if (empty && process()) {
                try (AutoLock.WithCondition l = lock.lock()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Notifying threads in waitFor()");
                    }
                    l.signalAll();
                }
            }
        }

        private boolean process() {
            boolean looping = false;
            while (true) {
                Runnable action;
                try (AutoLock ignored = lock.lock()) {
                    // Reentering is a no-operation.
                    if (!looping && active) {
                        return false;
                    }
                    action = actions.poll();
                    if (action == null) {
                        active = false;
                        return true;
                    }
                    if (!looping) {
                        active = looping = true;
                    }
                }
                action.run();
            }
        }

        private boolean send(TransportListener messageListener, List<Message.Mutable> messages) {
            if (isDisconnected()) {
                messagesFailure(new TransportException(null), messages);
                return false;
            } else {
                for (Message.Mutable message : messages) {
                    if (Channel.META_CONNECT.equals(message.getChannel())) {
                        Message existing;
                        try (AutoLock ignored = lock.lock()) {
                            existing = metaConnect;
                            metaConnect = message;
                        }
                        if (logger.isDebugEnabled()) {
                            if (existing != null) {
                                logger.debug("Overwriting existing /meta/connect {}", existing);
                            }
                            logger.debug("Sending /meta/connect {}", message);
                        }
                    }
                }
                transport.send(messageListener, messages);
                return true;
            }
        }

        private boolean matchMetaConnect(Message.Mutable connect) {
            try (AutoLock ignored = lock.lock()) {
                if (State.DISCONNECTED.implies(state)) {
                    return true;
                }
                if (metaConnect != null && metaConnect.getId().equals(connect.getId())) {
                    metaConnect = null;
                    return true;
                }
            }
            return false;
        }

        private Message getMetaConnect() {
            try (AutoLock ignored = lock.lock()) {
                return metaConnect;
            }
        }

        private boolean isIdle() {
            try (AutoLock ignored = lock.lock()) {
                return !active;
            }
        }

        private boolean await(long time) {
            try (AutoLock.WithCondition l = lock.lock()) {
                try {
                    l.await(time, TimeUnit.MILLISECONDS);
                    return false;
                } catch (InterruptedException x) {
                    return true;
                }
            }
        }

        private void initUnconnectTime() {
            try (AutoLock ignored = lock.lock()) {
                if (unconnectTime == 0) {
                    unconnectTime = NanoTime.now();
                }
            }
        }

        @Override
        public void handle(ClientTransport.FailureInfo failureInfo) {
            if (logger.isDebugEnabled()) {
                logger.debug("Transport failure handling: {}", failureInfo);
            }

            submit(() -> {
                State newState = failureInfo.actionToState();

                try (AutoLock ignored = lock.lock()) {
                    ClientTransport newTransport = failureInfo.transport;
                    if (newTransport != null) {
                        transport = newTransport;
                    }

                    String url = failureInfo.url;
                    if (url != null) {
                        transport.setURL(url);
                    }
                }

                switch (newState) {
                    case REHANDSHAKING -> {
                        rehandshaking(failureInfo.delay);
                    }
                    case UNCONNECTED -> {
                        unconnected(failureInfo.delay);
                    }
                    case TERMINATING -> {
                        terminating();
                    }
                    default -> {
                        throw new IllegalStateException("Could not handle transport failure in state " + newState);
                    }
                }
            });
        }

        @Override
        public String toString() {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), state);
        }
    }

    public static class Scheduler extends ScheduledThreadPoolExecutor {
        public Scheduler(int threads) {
            super(threads);
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            setRemoveOnCancelPolicy(true);
        }
    }
}
