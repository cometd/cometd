/*
 * Copyright (c) 2008-2016 the original author or authors.
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

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>{@link BayeuxClient} is the implementation of a client for the Bayeux protocol.</p>
 * <p> A {@link BayeuxClient} can receive/publish messages from/to a Bayeux server, and
 * it is the counterpart in Java of the JavaScript library used in browsers (and as such
 * it is ideal for Swing applications, load testing tools, etc.).</p>
 * <p>A {@link BayeuxClient} handshakes with a Bayeux server
 * and then subscribes {@link ClientSessionChannel.MessageListener} to channels in order
 * to receive messages, and may also publish messages to the Bayeux server.</p>
 * <p>{@link BayeuxClient} relies on pluggable transports for communication with the Bayeux
 * server, and the most common transport is {@link LongPollingTransport}, which uses
 * HTTP to transport Bayeux messages and it is based on
 * <a href="http://wiki.eclipse.org/Jetty/Feature/HttpClient">Jetty's HTTP client</a>.</p>
 * <p>When the communication with the server is finished, the {@link BayeuxClient} can be
 * disconnected from the Bayeux server.</p>
 * <p>Typical usage:</p>
 * <pre>
 * // Handshake
 * String url = "http://localhost:8080/cometd";
 * BayeuxClient client = new BayeuxClient(url, LongPollingTransport.create(null));
 * client.handshake();
 * client.waitFor(1000, BayeuxClient.State.CONNECTED);
 *
 * // Subscription to channels
 * ClientSessionChannel channel = client.getChannel("/foo");
 * channel.subscribe(new ClientSessionChannel.MessageListener()
 * {
 *     public void onMessage(ClientSessionChannel channel, Message message)
 *     {
 *         // Handle the message
 *     }
 * });
 *
 * // Publishing to channels
 * Map&lt;String, Object&gt; data = new HashMap&lt;String, Object&gt;();
 * data.put("bar", "baz");
 * channel.publish(data);
 *
 * // Disconnecting
 * client.disconnect();
 * client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
 * </pre>
 */
public class BayeuxClient extends AbstractClientSession implements Bayeux
{
    public static final String BACKOFF_INCREMENT_OPTION = "backoffIncrement";
    public static final String MAX_BACKOFF_OPTION = "maxBackoff";
    public static final String BAYEUX_VERSION = "1.0";

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName() + "." + Integer.toHexString(System.identityHashCode(this)));
    private final TransportRegistry transportRegistry = new TransportRegistry();
    private final Map<String, Object> options = new ConcurrentHashMap<>();
    private final List<Message.Mutable> messageQueue = new ArrayList<>(32);
    private final CookieStore cookieStore = new CookieManager().getCookieStore();
    private final TransportListener messageListener = new MessageTransportListener();
    private final String url;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean shutdownScheduler;
    private volatile long backoffIncrement;
    private volatile long maxBackoff;

    /**
     * <p>Creates a {@link BayeuxClient} that will connect to the Bayeux server at the given URL
     * and with the given transport(s).</p>
     * <p>This constructor allocates a new {@link ScheduledExecutorService scheduler}; it is recommended that
     * when creating a large number of {@link BayeuxClient}s a shared scheduler is used.</p>
     *
     * @param url        the Bayeux server URL to connect to
     * @param transport  the default (mandatory) transport to use
     * @param transports additional optional transports to use in case the default transport cannot be used
     * @see #BayeuxClient(String, ScheduledExecutorService, ClientTransport, ClientTransport...)
     */
    public BayeuxClient(String url, ClientTransport transport, ClientTransport... transports)
    {
        this(url, null, transport, transports);
    }

    /**
     * <p>Creates a {@link BayeuxClient} that will connect to the Bayeux server at the given URL,
     * with the given scheduler and with the given transport(s).</p>
     *
     * @param url        the Bayeux server URL to connect to
     * @param scheduler  the scheduler to use for scheduling timed operations
     * @param transport  the default (mandatory) transport to use
     * @param transports additional optional transports to use in case the default transport cannot be used
     */
    public BayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport transport, ClientTransport... transports)
    {
        this.url = Objects.requireNonNull(url);
        this.scheduler = scheduler;

        transport = Objects.requireNonNull(transport);
        transportRegistry.add(transport);
        for (ClientTransport t : transports)
            transportRegistry.add(t);

        for (String transportName : transportRegistry.getKnownTransports())
        {
            ClientTransport clientTransport = transportRegistry.getTransport(transportName);
            if (clientTransport instanceof MessageClientTransport)
            {
                ((MessageClientTransport)clientTransport).setMessageTransportListener(messageListener);
            }
            if (clientTransport instanceof HttpClientTransport)
            {
                HttpClientTransport httpTransport = (HttpClientTransport)clientTransport;
                httpTransport.setURL(url);
                httpTransport.setCookieStore(cookieStore);
            }
        }
    }

    /**
     * @return the URL passed when constructing this instance
     */
    public String getURL()
    {
        return url;
    }

    /**
     * @return the period of time that increments the pause to wait before trying to reconnect
     *         after each failed attempt to connect to the Bayeux server
     * @see #getMaxBackoff()
     */
    public long getBackoffIncrement()
    {
        return backoffIncrement;
    }

    /**
     * @return the maximum pause to wait before trying to reconnect after each failed attempt
     *         to connect to the Bayeux server
     * @see #getBackoffIncrement()
     */
    public long getMaxBackoff()
    {
        return maxBackoff;
    }

    public CookieStore getCookieStore()
    {
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
    public HttpCookie getCookie(String name)
    {
        for (HttpCookie cookie : getCookieStore().get(URI.create(getURL())))
        {
            if (name.equals(cookie.getName()))
                return cookie;
        }
        return null;
    }

    public void putCookie(HttpCookie cookie)
    {
        URI uri = URI.create(getURL());
        if (cookie.getPath() == null)
        {
            String path = uri.getPath();
            if (path == null || !path.contains("/"))
                path = "/";
            else
                path = path.substring(0, path.lastIndexOf("/") + 1);
            cookie.setPath(path);
        }
        if (cookie.getDomain() == null)
            cookie.setDomain(uri.getHost());
        getCookieStore().add(uri, cookie);
    }

    @Override
    public String getId()
    {
        return sessionState.getSessionId();
    }

    @Override
    public boolean isHandshook()
    {
        State state = getState();
        return state == State.HANDSHAKEN || state == State.CONNECTING || state == State.CONNECTED || state == State.UNCONNECTED;
    }

    @Override
    public boolean isConnected()
    {
        return getState() == State.CONNECTED;
    }

    /**
     * @return whether this {@link BayeuxClient} is disconnecting or disconnected
     */
    public boolean isDisconnected()
    {
        State state = getState();
        return state == State.TERMINATING || state == State.DISCONNECTED;
    }

    /**
     * @return the current state of this {@link BayeuxClient}
     */
    protected State getState()
    {
        return sessionState.getState();
    }

    @Override
    public void handshake()
    {
        handshake(null, null);
    }

    @Override
    public void handshake(final Map<String, Object> handshakeFields)
    {
        handshake(handshakeFields, null);
    }

    public void handshake(ClientSessionChannel.MessageListener callback)
    {
        handshake(null, callback);
    }

    @Override
    public void handshake(final Map<String, Object> fields, final ClientSessionChannel.MessageListener callback)
    {
        initialize();

        List<String> allowedTransports = getAllowedTransports();
        // Pick the first transport for the handshake, it will renegotiate if not right
        final ClientTransport transport = transportRegistry.negotiate(allowedTransports.toArray(), BAYEUX_VERSION).get(0);
        prepareTransport(null, transport);
        if (logger.isDebugEnabled())
            logger.debug("Using initial transport {} from {}", transport.getName(), allowedTransports);

        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                sessionState.handshaking(transport, fields, callback);
            }
        });
    }

    /**
     * <p>Performs the handshake and waits at most the given time for the handshake to complete.</p>
     * <p>When this method returns, the handshake may have failed (for example because the Bayeux
     * server denied it), so it is important to check the return value to know whether the handshake
     * completed or not.</p>
     *
     * @param waitMs the time to wait for the handshake to complete
     * @return the state of this {@link BayeuxClient}
     * @see #handshake(Map, long)
     */
    public State handshake(long waitMs)
    {
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
     * @return the state of this {@link BayeuxClient}
     * @see #handshake(long)
     */
    public State handshake(Map<String, Object> template, long waitMs)
    {
        handshake(template);
        waitFor(waitMs, State.CONNECTING, State.CONNECTED, State.DISCONNECTED);
        return getState();
    }

    protected boolean sendHandshake()
    {
        List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
        List<String> transportNames = new ArrayList<>(transports.size());
        for (ClientTransport transport : transports)
            transportNames.add(transport.getName());

        Message.Mutable message = newMessage();
        Map<String, Object> handshakeFields = sessionState.getHandshakeFields();
        if (handshakeFields != null)
            message.putAll(handshakeFields);

        String messageId = newMessageId();
        message.setId(messageId);
        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transportNames);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        registerCallback(messageId, sessionState.getHandshakeCallback());

        if (logger.isDebugEnabled())
            logger.debug("Handshaking on transport {}: {}", getTransport(), message);

        List<Message.Mutable> messages = new ArrayList<>(1);
        messages.add(message);
        return sendMessages(messages);
    }

    /**
     * <p>Waits for this {@link BayeuxClient} to reach the given state(s) within the given time.</p>
     *
     * @param waitMs the time to wait to reach the given state(s)
     * @param state  the state to reach
     * @param states additional states to reach in alternative
     * @return true if one of the state(s) has been reached within the given time, false otherwise
     */
    public boolean waitFor(long waitMs, State state, State... states)
    {
        List<State> waitForStates = new ArrayList<>();
        waitForStates.add(state);
        waitForStates.addAll(Arrays.asList(states));

        synchronized (sessionState)
        {
            while (waitMs > 0)
            {
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
                if (sessionState.isIdle())
                {
                    State currentState = getState();
                    for (State s : waitForStates)
                    {
                        if (currentState.implies(s))
                            return true;
                    }
                }

                if (logger.isDebugEnabled())
                    logger.debug("Waiting {}ms for {}", waitMs, waitForStates);

                long start = System.nanoTime();
                if (sessionState.await(waitMs))
                    break;
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                if (logger.isDebugEnabled())
                    logger.debug("Waited {}/{}ms for {}, state is {}", elapsed, waitMs, waitForStates, sessionState.getState());

                waitMs -= elapsed;
            }
            return false;
        }
    }

    protected boolean sendConnect()
    {
        ClientTransport transport = sessionState.getTransport();

        Message.Mutable message = newMessage();
        message.setId(newMessageId());
        message.setChannel(Channel.META_CONNECT);
        message.put(Message.CONNECTION_TYPE_FIELD, transport.getName());

        State state = getState();
        if (state == State.CONNECTING || state == State.UNCONNECTED)
        {
            // First connect after handshake or after failure, add advice
            message.getAdvice(true).put("timeout", 0);
        }

        if (logger.isDebugEnabled())
            logger.debug("Connecting, transport {}", transport);

        List<Message.Mutable> messages = new ArrayList<>(1);
        messages.add(message);
        return sendMessages(messages);
    }

    @Override
    protected ChannelId newChannelId(String channelId)
    {
        // Save some parsing by checking if there is already one
        AbstractSessionChannel channel = getChannels().get(channelId);
        return channel == null ? new ChannelId(channelId) : channel.getChannelId();
    }

    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId)
    {
        return new BayeuxClientChannel(channelId);
    }

    @Override
    protected void sendBatch()
    {
        if (canSend())
        {
            List<Message.Mutable> messages = takeMessages();
            if (!messages.isEmpty())
                sendMessages(messages);
        }
    }

    protected boolean sendMessages(List<Message.Mutable> messages)
    {
        for (Iterator<Message.Mutable> iterator = messages.iterator(); iterator.hasNext();)
        {
            Message.Mutable message = iterator.next();

            String messageId = message.getId();
            message.setClientId(sessionState.sessionId);

            if (extendSend(message))
            {
                // Extensions may have changed the messageId, but we need to
                // own it in case of meta messages to link requests to responses
                // in non request/response transports such as WebSocket.
                message.setId(messageId);
            }
            else
            {
                iterator.remove();
            }
        }

        if (messages.isEmpty())
            return false;

        if (logger.isDebugEnabled())
            logger.debug("Sending messages {}", messages);

        return sessionState.send(messageListener, messages);
    }

    private List<Message.Mutable> takeMessages()
    {
        // Multiple threads can call this method concurrently (for example
        // a batched publish() is executed exactly when a message arrives
        // and a listener also performs a batched publish() in response to
        // the message).
        // The queue must be drained atomically, otherwise we risk that the
        // same message is drained twice.

        List<Message.Mutable> messages;
        synchronized (messageQueue)
        {
            messages = new ArrayList<>(messageQueue);
            messageQueue.clear();
        }
        return messages;
    }

    /**
     * @see #disconnect(long)
     */
    @Override
    public void disconnect()
    {
        disconnect(null);
    }

    @Override
    public void disconnect(final ClientSessionChannel.MessageListener callback)
    {
        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                if (sessionState.disconnecting())
                {
                    Message.Mutable message = newMessage();
                    String messageId = newMessageId();
                    message.setId(messageId);
                    message.setChannel(Channel.META_DISCONNECT);

                    registerCallback(messageId, callback);

                    List<Message.Mutable> messages = new ArrayList<>(1);
                    messages.add(message);
                    sendMessages(messages);
                }
            }
        });
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
    public boolean disconnect(long timeout)
    {
        if (isDisconnected())
            return true;

        final CountDownLatch latch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener lastConnectListener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                final Map<String, Object> advice = message.getAdvice();
                if (!message.isSuccessful() ||
                        advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)))
                    latch.countDown();
            }
        };
        getChannel(Channel.META_CONNECT).addListener(lastConnectListener);

        disconnect();
        boolean disconnected = waitFor(timeout, BayeuxClient.State.DISCONNECTED);

        // There is a possibility that we are in the window where the server
        // has returned the long poll and the client has not issued it again,
        // so wait for the timeout, but do not complain if the latch does not trigger.
        try
        {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
        }

        getChannel(Channel.META_CONNECT).removeListener(lastConnectListener);

        // Force termination.
        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                sessionState.terminating();
            }
        });

        return disconnected;
    }

    /**
     * <p>Interrupts abruptly the communication with the Bayeux server.</p>
     * <p>This method may be useful to simulate network failures.</p>
     *
     * @see #disconnect()
     */
    public void abort()
    {
        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                if (sessionState.update(State.TERMINATING))
                    sessionState.terminate(true);
            }
        });
    }

    private void processMessages(List<Message.Mutable> messages)
    {
        for (Message.Mutable message : messages)
        {
            if (logger.isDebugEnabled())
                logger.debug("Processing {}", message);

            switch (message.getChannel())
            {
                case Channel.META_HANDSHAKE:
                {
                    processHandshake(message);
                    break;
                }
                case Channel.META_CONNECT:
                {
                    processConnect(message);
                    break;
                }
                case Channel.META_DISCONNECT:
                {
                    processDisconnect(message);
                    break;
                }
                default:
                {
                    processMessage(message);
                    break;
                }
            }
        }
    }

    private void messagesFailure(Throwable failure, List<? extends Message> messages)
    {
        for (Message message : messages)
        {
            if (logger.isDebugEnabled())
                logger.debug("Failing {}", message);

            switch (message.getChannel())
            {
                case Channel.META_HANDSHAKE:
                {
                    handshakeFailure(message, failure);
                    break;
                }
                case Channel.META_CONNECT:
                {
                    connectFailure(message, failure);
                    break;
                }
                case Channel.META_DISCONNECT:
                {
                    disconnectFailure(message, failure);
                    break;
                }
                default:
                {
                    messageFailure(message, failure);
                    break;
                }
            }
        }
    }

    protected void processHandshake(final Message.Mutable handshake)
    {
        if (handshake.isSuccessful())
        {
            final ClientTransport oldTransport = getTransport();

            Object field = handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD);
            Object[] serverTransports = field instanceof List ? ((List)field).toArray() : (Object[])field;
            final List<ClientTransport> negotiatedTransports = transportRegistry.negotiate(serverTransports, BAYEUX_VERSION);
            if (negotiatedTransports.isEmpty())
            {
                // Signal the failure
                String error = "405:c" +
                        getAllowedTransports() +
                        ",s" +
                        Arrays.toString(serverTransports) +
                        ":no transport";

                handshake.setSuccessful(false);
                handshake.put(Message.ERROR_FIELD, error);

                receive(handshake);

                sessionState.submit(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (sessionState.update(State.TERMINATING))
                        {
                            onTransportFailure(oldTransport.getName(), null, new TransportException(null));
                            sessionState.terminate(false);
                        }
                    }
                });
            }
            else
            {
                Number messagesField = (Number)handshake.get("x-messages");
                final int messages = messagesField == null ? 0 : messagesField.intValue();

                final ClientTransport newTransport = negotiatedTransports.get(0);
                if (newTransport != oldTransport)
                    prepareTransport(oldTransport, newTransport);

                sessionState.submit(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (sessionState.handshaken(newTransport, handshake.getAdvice(), handshake.getClientId(), messages))
                        {
                            receive(handshake);
                            sendBatch();
                            if (messages == 0)
                                sessionState.connecting();
                        }
                    }
                });
            }
        }
        else
        {
            receive(handshake);

            sessionState.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    String action = sessionState.getAdviceAction(handshake.getAdvice(), Message.RECONNECT_HANDSHAKE_VALUE);
                    if (action.equals(Message.RECONNECT_NONE_VALUE))
                    {
                        sessionState.terminating();
                    }
                    else
                    {
                        sessionState.rehandshaking(null, false);
                    }
                }
            });
        }
    }

    private void handshakeFailure(Message handshake, final Throwable failure)
    {
        failMessage(handshake, failure);

        final ClientTransport oldTransport = getTransport();
        List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
        if (transports.isEmpty())
        {
            sessionState.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    if (sessionState.update(State.TERMINATING))
                    {
                        onTransportFailure(oldTransport.getName(), null, failure);
                        sessionState.terminate(false);
                    }
                }
            });
        }
        else
        {
            final ClientTransport newTransport = transports.get(0);
            if (newTransport != oldTransport)
                prepareTransport(oldTransport, newTransport);

            sessionState.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    // TODO: we should only notify if we could move into rehandshaking state, but before its actions.
                    // TODO: beware that this could produce an NPE if one of the transport is null.
                    onTransportFailure(oldTransport.getName(), newTransport.getName(), failure);
                    sessionState.rehandshaking(newTransport, false);
                }
            });
        }
    }

    protected void processConnect(final Message.Mutable connect)
    {
        receive(connect);

        final Map<String, Object> advice = connect.getAdvice();
        final String action = getAdviceAction(advice, Message.RECONNECT_RETRY_VALUE);

        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                if (Message.RECONNECT_NONE_VALUE.equals(action))
                {
                    sessionState.terminating();
                }
                else if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
                {
                    sessionState.rehandshaking(null, true);
                }
                else
                {
                    if (connect.isSuccessful())
                        sessionState.connected(advice);
                    else
                        sessionState.unconnected();
                }
            }
        });
    }

    private void connectFailure(Message connect, Throwable failure)
    {
        failMessage(connect, failure);

        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                sessionState.unconnected();
            }
        });
    }

    protected void processDisconnect(final Message.Mutable disconnect)
    {
        receive(disconnect);

        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                sessionState.terminating();
            }
        });
    }

    private void disconnectFailure(Message disconnect, Throwable failure)
    {
        failMessage(disconnect, failure);

        sessionState.submit(new Runnable()
        {
            @Override
            public void run()
            {
                sessionState.terminating();
            }
        });
    }

    protected void processMessage(Message.Mutable message)
    {
        if (logger.isDebugEnabled())
            logger.debug("Processing message {}", message);
        receive(message);
        if (getState() == State.HANDSHAKEN)
        {
            sessionState.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    sessionState.connecting();
                }
            });
        }
    }

    private void messageFailure(Message message, Throwable failure)
    {
        failMessage(message, failure);
    }

    private String getAdviceAction(Map<String, Object> advice, String defaultResult)
    {
        String action = defaultResult;
        if (advice != null && advice.containsKey(Message.RECONNECT_FIELD))
            action = (String)advice.get(Message.RECONNECT_FIELD);
        return action;
    }

    protected boolean scheduleHandshake(long interval, long backOff)
    {
        if (logger.isDebugEnabled())
            logger.debug("Scheduled handshake in {}+{} ms", interval, backOff);
        return scheduleAction(new Runnable()
        {
            public void run()
            {
                sendHandshake();
            }
        }, interval, backOff);
    }

    protected boolean scheduleConnect(long interval, long backOff)
    {
        if (logger.isDebugEnabled())
            logger.debug("Scheduled connect in {}+{} ms", interval, backOff);
        return scheduleAction(new Runnable()
        {
            public void run()
            {
                sendConnect();
            }
        }, interval, backOff);
    }

    private boolean scheduleAction(Runnable action, long interval, long backoff)
    {
        // Prevent NPE in case of concurrent disconnect
        ScheduledExecutorService scheduler = this.scheduler;
        if (scheduler != null)
        {
            try
            {
                scheduler.schedule(action, interval + backoff, TimeUnit.MILLISECONDS);
                return true;
            }
            catch (RejectedExecutionException x)
            {
                // It has been shut down
                logger.trace("", x);
            }
        }
        if (logger.isDebugEnabled())
            logger.debug("Could not schedule action {} to scheduler {}", action, scheduler);
        return false;
    }

    @Override
    public List<String> getAllowedTransports()
    {
        return transportRegistry.getAllowedTransports();
    }

    @Override
    public Set<String> getKnownTransportNames()
    {
        return transportRegistry.getKnownTransports();
    }

    @Override
    public ClientTransport getTransport(String transport)
    {
        return transportRegistry.getTransport(transport);
    }

    public ClientTransport getTransport()
    {
        return sessionState.getTransport();
    }

    protected void initialize()
    {
        Number value = (Number)getOption(BACKOFF_INCREMENT_OPTION);
        long backoffIncrement = value == null ? -1 : value.longValue();
        if (backoffIncrement < 0)
            backoffIncrement = 1000L;
        this.backoffIncrement = backoffIncrement;

        value = (Number)getOption(MAX_BACKOFF_OPTION);
        long maxBackoff = value == null ? -1 : value.longValue();
        if (maxBackoff <= 0)
            maxBackoff = 30000L;
        this.maxBackoff = maxBackoff;

        if (scheduler == null)
        {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            shutdownScheduler = true;
        }
    }

    protected void terminate()
    {
        List<Message.Mutable> messages = takeMessages();
        failMessages(null, messages);

        cookieStore.removeAll();

        if (shutdownScheduler)
        {
            shutdownScheduler = false;
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public Object getOption(String qualifiedName)
    {
        return options.get(qualifiedName);
    }

    @Override
    public void setOption(String qualifiedName, Object value)
    {
        options.put(qualifiedName, value);
        // Forward the option to the transports.
        for (String name : transportRegistry.getKnownTransports())
        {
            ClientTransport transport = transportRegistry.getTransport(name);
            transport.setOption(qualifiedName, value);
        }
    }

    @Override
    public Set<String> getOptionNames()
    {
        return options.keySet();
    }

    /**
     * @return the options that configure with {@link BayeuxClient}
     */
    public Map<String, Object> getOptions()
    {
        return Collections.unmodifiableMap(options);
    }

    @Override
    protected void send(Message.Mutable message)
    {
        enqueueSend(message);
    }

    protected void enqueueSend(Message.Mutable message)
    {
        if (canSend())
        {
            List<Message.Mutable> messages = new ArrayList<>(1);
            messages.add(message);
            boolean sent = sendMessages(messages);
            if (logger.isDebugEnabled())
                logger.debug("{} message {}", sent ? "Sent" : "Failed", message);
        }
        else
        {
            synchronized (messageQueue)
            {
                messageQueue.add(message);
            }
            if (logger.isDebugEnabled())
                logger.debug("Enqueued message {} (batching: {})", message, isBatching());
        }
    }

    private boolean canSend()
    {
        State state = getState();
        boolean handshaking = state == State.HANDSHAKING || state == State.REHANDSHAKING;
        return !isBatching() && !handshaking;
    }

    protected void failMessages(Throwable x, List<? extends Message> messages)
    {
        for (Message message : messages)
            failMessage(message, x);
    }

    protected void failMessage(Message message, Throwable x)
    {
        Message.Mutable failed = newMessage();
        failed.setId(message.getId());
        failed.setSuccessful(false);
        failed.setChannel(message.getChannel());
        failed.put(Message.SUBSCRIPTION_FIELD, message.get(Message.SUBSCRIPTION_FIELD));

        Map<String, Object> failure = new HashMap<>();
        failed.put("failure", failure);
        failure.put("message", message);
        if (x != null)
            failure.put("exception", x);
        if (x instanceof TransportException)
        {
            Map<String, Object> fields = ((TransportException)x).getFields();
            if (fields != null)
                failure.putAll(fields);
        }
        ClientTransport transport = getTransport();
        if (transport != null)
            failure.put(Message.CONNECTION_TYPE_FIELD, transport.getName());

        receive(failed);
    }

    /**
     * <p>Callback method invoked when the given messages have hit the network towards the Bayeux server.</p>
     * <p>The messages may not be modified, and any modification will be useless because the message have
     * already been sent.</p>
     *
     * @param messages the messages sent
     */
    public void onSending(List<? extends Message> messages)
    {
    }

    /**
     * <p>Callback method invoke when the given messages have just arrived from the Bayeux server.</p>
     * <p>The messages may be modified, but it's suggested to use {@link Extension}s instead.</p>
     * <p>Extensions will be processed after the invocation of this method.</p>
     *
     * @param messages the messages arrived
     */
    public void onMessages(List<Message.Mutable> messages)
    {
    }

    /**
     * <p>Callback method invoked when the given messages have failed to be sent.</p>
     * <p>The default implementation logs the failure at DEBUG level.</p>
     *
     * @param failure        the exception that caused the failure
     * @param messages the messages being sent
     */
    public void onFailure(Throwable failure, List<? extends Message> messages)
    {
        if (logger.isDebugEnabled())
            logger.debug("Messages failed " + messages, failure);
    }

    public String dump()
    {
        StringBuilder b = new StringBuilder();
        dump(b, "");
        return b.toString();
    }

    private void prepareTransport(ClientTransport oldTransport, ClientTransport newTransport)
    {
        if (oldTransport != null)
            oldTransport.terminate();
        newTransport.init();
    }

    protected void onTransportFailure(String oldTransportName, String newTransportName, Throwable failure)
    {
    }

    /**
     * The states that a {@link BayeuxClient} may assume
     */
    public enum State
    {
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
         * State assumed when this {@link BayeuxClient} is connected to the Bayeux server
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

        private State(State... implieds)
        {
            this.implieds = implieds;
        }

        private boolean implies(State state)
        {
            if (state == this)
                return true;
            for (State implied : implieds)
            {
                if (state == implied)
                    return true;
            }
            return false;
        }

        private boolean isUpdateableTo(State newState)
        {
            switch (this)
            {
                case DISCONNECTED:
                    return newState == HANDSHAKING;
                case HANDSHAKING:
                case REHANDSHAKING:
                    return EnumSet.of(REHANDSHAKING, HANDSHAKEN, DISCONNECTING, TERMINATING).contains(newState);
                case HANDSHAKEN:
                    return EnumSet.of(CONNECTING, DISCONNECTING, TERMINATING).contains(newState);
                case CONNECTING:
                case CONNECTED:
                case UNCONNECTED:
                    return EnumSet.of(REHANDSHAKING, CONNECTED, UNCONNECTED, DISCONNECTING, TERMINATING).contains(newState);
                case DISCONNECTING:
                    return newState == TERMINATING;
                case TERMINATING:
                    return newState == DISCONNECTED;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private class MessageTransportListener implements TransportListener
    {
        @Override
        public void onSending(List<? extends Message> messages)
        {
            BayeuxClient.this.onSending(messages);
        }

        @Override
        public void onMessages(final List<Message.Mutable> messages)
        {
            BayeuxClient.this.onMessages(messages);
            processMessages(messages);
        }

        @Override
        public void onFailure(final Throwable failure, final List<? extends Message> messages)
        {
            BayeuxClient.this.onFailure(failure, messages);
            messagesFailure(failure, messages);
        }
    }

    /**
     * <p>A channel scoped to this BayeuxClient.</p>
     */
    protected class BayeuxClientChannel extends AbstractSessionChannel
    {
        protected BayeuxClientChannel(ChannelId channelId)
        {
            super(channelId);
        }

        @Override
        public ClientSession getSession()
        {
            throwIfReleased();
            return BayeuxClient.this;
        }
    }

    private final SessionState sessionState = new SessionState();

    private class SessionState
    {
        private final Queue<Runnable> actions = new ArrayDeque<>();
        private State state = State.DISCONNECTED;
        private ClientTransport transport;
        private Map<String, Object> handshakeFields;
        private ClientSessionChannel.MessageListener handshakeCallback;
        private Map<String, Object> advice;
        private String sessionId;
        private long backOff;
        private long unconnected;
        private boolean active;
        private boolean waiting;
        private int handshakeMessages;

        private void reset()
        {
            actions.clear();
            state = State.DISCONNECTED;
            transport = null;
            handshakeFields = null;
            handshakeCallback = null;
            advice = null;
            sessionId = null;
            backOff = 0;
            unconnected = 0;
            active = false;
            waiting = false;
            handshakeMessages = 0;
        }

        private State getState()
        {
            synchronized (this)
            {
                return state;
            }
        }

        private ClientTransport getTransport()
        {
            synchronized (this)
            {
                return transport;
            }
        }

        private Map<String, Object> getHandshakeFields()
        {
            synchronized (this)
            {
                return handshakeFields;
            }
        }

        private ClientSessionChannel.MessageListener getHandshakeCallback()
        {
            synchronized (this)
            {
                return handshakeCallback;
            }
        }

        private Map<String, Object> getAdvice()
        {
            synchronized (this)
            {
                return advice;
            }
        }

        private String getSessionId()
        {
            synchronized (this)
            {
                return sessionId;
            }
        }

        private long getBackOff()
        {
            synchronized (this)
            {
                return backOff;
            }
        }

        public long getUnconnected()
        {
            synchronized (this)
            {
                if (unconnected == 0)
                    return 0;
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - unconnected);
            }
        }

        private long getTimeout()
        {
            return getAdviceLong(Message.TIMEOUT_FIELD);
        }

        private long getInterval()
        {
            return getAdviceLong(Message.INTERVAL_FIELD);
        }

        private long getMaxInterval()
        {
            return getAdviceLong(Message.MAX_INTERVAL_FIELD);
        }

        private String getAdviceAction(Map<String, Object> advice, String defaultAction)
        {
            String result = null;
            if (advice == null)
                advice = getAdvice();
            if (advice != null)
                result = (String)advice.get(Message.RECONNECT_FIELD);
            return result == null ? defaultAction : result;
        }

        private long getAdviceLong(String field)
        {
            synchronized (this)
            {
                long result = 0;
                if (advice != null && advice.containsKey(field))
                    result = ((Number)advice.get(field)).longValue();
                return result;
            }
        }

        private long nextBackOff()
        {
            synchronized (this)
            {
                return Math.min(backOff + getBackoffIncrement(), getMaxBackoff());
            }
        }

        public boolean handshaking(ClientTransport transport, Map<String, Object> fields, ClientSessionChannel.MessageListener callback)
        {
            boolean result;
            synchronized (this)
            {
                reset();
                result = update(State.HANDSHAKING);
                if (result)
                {
                    this.transport = transport;
                    this.handshakeFields = fields;
                    this.handshakeCallback = callback;
                }
            }
            if (result)
            {
                resetSubscriptions();
                sendHandshake();
            }
            return result;
        }

        public boolean rehandshaking(ClientTransport transport, boolean resetBackOff)
        {
            State oldState;
            long oldBackOff;
            boolean result;
            synchronized (this)
            {
                oldState = this.state;
                oldBackOff = resetBackOff ? 0 : this.backOff;
                result = sessionState.update(State.REHANDSHAKING);
                if (result)
                {
                    this.backOff = resetBackOff ? 0 : nextBackOff();
                    if (transport != null)
                        this.transport = transport;
                }
            }

            if (result)
            {
                if (oldState != State.HANDSHAKING)
                    resetSubscriptions();
                scheduleHandshake(getInterval(), oldBackOff);
            }

            return result;
        }

        public boolean handshaken(ClientTransport transport, Map<String, Object> advice, String sessionId, int messages)
        {
            synchronized (this)
            {
                if (update(State.HANDSHAKEN))
                {
                    this.transport = transport;
                    this.advice = advice;
                    this.sessionId = sessionId;
                    this.handshakeMessages = messages;
                    this.backOff = 0;
                    return true;
                }
                return false;
            }
        }

        public boolean connecting()
        {
            boolean result = false;
            synchronized (this)
            {
                if (handshakeMessages > 0)
                    --handshakeMessages;
                if (handshakeMessages == 0)
                    result = sessionState.update(State.CONNECTING);
            }
            if (result)
                scheduleConnect(getInterval(), 0);
            return result;
        }

        public boolean connected(Map<String, Object> advice)
        {
            long backOff;
            boolean result;
            synchronized (this)
            {
                backOff = this.backOff;
                result = update(State.CONNECTED);
                if (result && advice != null)
                    this.advice = advice;
            }

            if (result)
                scheduleConnect(getInterval(), backOff);

            return result;
        }

        public boolean unconnected()
        {
            long backOff;
            boolean handshake;
            boolean result;
            synchronized (this)
            {
                backOff = this.backOff;
                handshake = false;
                result = update(State.UNCONNECTED);
                if (result)
                {
                    if (unconnected == 0)
                        unconnected = System.nanoTime();

                    long maxInterval = getMaxInterval();
                    if (maxInterval > 0)
                    {
                        long expiration = getTimeout() + getInterval() + maxInterval;
                        long unconnected = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.unconnected);
                        handshake = unconnected + this.backOff > expiration;
                    }

                    if (handshake)
                        this.backOff = backOff = 0;
                    else
                        this.backOff = backOff = nextBackOff();
                }
            }

            if (result)
            {
                if (handshake)
                {
                    if (logger.isDebugEnabled())
                        logger.debug("Switching to handshake retries");
                    scheduleHandshake(getInterval(), backOff);
                }
                else
                {
                    scheduleConnect(getInterval(), backOff);
                }
            }

            return result;
        }

        private boolean disconnecting()
        {
            return update(State.DISCONNECTING);
        }

        private void terminating()
        {
            if (update(State.TERMINATING))
                terminate(false);
        }

        private boolean update(State newState)
        {
            synchronized (this)
            {
                State oldState = state;
                boolean result = state.isUpdateableTo(newState);
                if (result)
                    state = newState;
                if (logger.isDebugEnabled())
                    logger.debug("State {}updated: {} -> {}", result ? "" : "not ", oldState, newState);
                return result;
            }
        }

        private void terminate(boolean abort)
        {
            if (abort)
                transport.abort();
            else
                transport.terminate();
            BayeuxClient.this.terminate();
            update(State.DISCONNECTED);
        }

        private void submit(Runnable action)
        {
            boolean empty;
            synchronized (this)
            {
                empty = actions.isEmpty();
                actions.offer(action);
            }
            if (empty && process())
            {
                synchronized (this)
                {
                    if (waiting)
                    {
                        if (logger.isDebugEnabled())
                            logger.debug("Notifying threads in waitFor()");
                        notifyAll();
                    }
                }
            }
        }

        private boolean process()
        {
            boolean looping = false;
            while (true)
            {
                Runnable action;
                synchronized (this)
                {
                    // Reentering is a no-operation.
                    if (!looping && active)
                        return false;

                    action = actions.poll();
                    if (action == null)
                    {
                        active = false;
                        return true;
                    }

                    if (!looping)
                        active = looping = true;
                }

                action.run();
            }
        }

        private boolean send(TransportListener messageListener, List<Message.Mutable> messages)
        {
            if (isDisconnected())
            {
                failMessages(new TransportException(null), messages);
                return false;
            }
            else
            {
                transport.send(messageListener, messages);
                return true;
            }
        }

        private boolean isIdle()
        {
            synchronized (this)
            {
                return !active;
            }
        }

        private boolean await(long time)
        {
            synchronized (this)
            {
                waiting = true;
                try
                {
                    wait(time);
                    return false;
                }
                catch (InterruptedException x)
                {
                    return true;
                }
                finally
                {
                    waiting = false;
                }
            }
        }
    }
}
