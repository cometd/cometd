/*
 * Copyright (c) 2010 the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.cometd.common.HashMapMessage;
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

    private final Logger logger = LoggerFactory.getLogger(getClass().getName() + "." + System.identityHashCode(this));
    private final TransportRegistry transportRegistry = new TransportRegistry();
    private final Map<String, Object> options = new ConcurrentHashMap<>();
    private final AtomicReference<BayeuxClientState> bayeuxClientState = new AtomicReference<>();
    private final List<Message.Mutable> messageQueue = new ArrayList<>(32);
    private final HttpClientTransport.CookieProvider cookieProvider = new HttpClientTransport.StandardCookieProvider();
    private final TransportListener handshakeListener = new HandshakeTransportListener();
    private final TransportListener connectListener = new ConnectTransportListener();
    private final TransportListener disconnectListener = new DisconnectTransportListener();
    private final TransportListener publishListener = new PublishTransportListener();
    private final Map<String, ClientSessionChannel.MessageListener> publishCallbacks = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean shutdownScheduler;
    private volatile long backoffIncrement;
    private volatile long maxBackoff;
    private int stateUpdaters;
    private boolean debug;

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
        if (transport == null)
            throw new IllegalArgumentException("Transport cannot be null");

        this.scheduler = scheduler;

        transportRegistry.add(transport);
        for (ClientTransport t : transports)
            transportRegistry.add(t);

        for (String transportName : transportRegistry.getKnownTransports())
        {
            ClientTransport clientTransport = transportRegistry.getTransport(transportName);
            if (clientTransport instanceof MessageClientTransport)
            {
                ((MessageClientTransport)clientTransport).setMessageTransportListener(publishListener);
            }
            if (clientTransport instanceof HttpClientTransport)
            {
                HttpClientTransport httpTransport = (HttpClientTransport)clientTransport;
                httpTransport.setURL(url);
                httpTransport.setCookieProvider(cookieProvider);
            }
        }

        bayeuxClientState.set(new DisconnectedState(null));
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

    /**
     * <p>Retrieves the cookie with the given name, if available.</p>
     * <p>Note that currently only HTTP transports support cookies.</p>
     *
     * @param name the cookie name
     * @return the cookie value
     * @see #setCookie(String, String)
     */
    public String getCookie(String name)
    {
        HttpClientTransport.Cookie cookie = cookieProvider.getCookie(name);
        if (cookie != null)
            return cookie.getValue();
        return null;
    }

    /**
     * <p>Sets a cookie that never expires.</p>
     *
     * @param name  the cookie name
     * @param value the cookie value
     * @see #setCookie(String, String, int)
     */
    public void setCookie(String name, String value)
    {
        setCookie(name, value, -1);
    }

    /**
     * <p>Sets a cookie with the given max age in seconds.</p>
     *
     * @param name   the cookie name
     * @param value  the cookie value
     * @param maxAge the max age of the cookie, in seconds, before expiration
     */
    public void setCookie(String name, String value, int maxAge)
    {
        HttpClientTransport.Cookie cookie = new HttpClientTransport.Cookie(name, value, null, null, maxAge, false, 0, null);
        cookieProvider.setCookie(cookie);
    }

    public String getId()
    {
        return bayeuxClientState.get().clientId;
    }

    public boolean isHandshook()
    {
        return isHandshook(bayeuxClientState.get());
    }

    private boolean isHandshook(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.CONNECTING ||
                bayeuxClientState.type == State.CONNECTED ||
                bayeuxClientState.type == State.UNCONNECTED;
    }

    private boolean isHandshaking(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.HANDSHAKING ||
                bayeuxClientState.type == State.REHANDSHAKING;
    }

    private boolean isConnecting(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.CONNECTING;
    }

    public boolean isConnected()
    {
        return isConnected(bayeuxClientState.get());
    }

    private boolean isConnected(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.CONNECTED;
    }

    private boolean isDisconnecting(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.DISCONNECTING;
    }

    private boolean isDisconnected(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.DISCONNECTED;
    }

    /**
     * @return whether this {@link BayeuxClient} is disconnecting or disconnected
     */
    public boolean isDisconnected()
    {
        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        return isDisconnecting(bayeuxClientState) || isDisconnected(bayeuxClientState);
    }

    /**
     * @return the current state of this {@link BayeuxClient}
     */
    protected State getState()
    {
        return bayeuxClientState.get().type;
    }

    public void handshake()
    {
        handshake(null);
    }

    public void handshake(final Map<String, Object> handshakeFields)
    {
        initialize();

        List<String> allowedTransports = getAllowedTransports();
        // Pick the first transport for the handshake, it will renegotiate if not right
        final ClientTransport initialTransport = transportRegistry.negotiate(allowedTransports.toArray(), BAYEUX_VERSION).get(0);
        initialTransport.init();
        debug("Using initial transport {} from {}", initialTransport.getName(), allowedTransports);

        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                return new HandshakingState(handshakeFields, initialTransport);
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
        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        if (isHandshaking(bayeuxClientState))
        {
            Message.Mutable message = newMessage();
            if (bayeuxClientState.handshakeFields != null)
                message.putAll(bayeuxClientState.handshakeFields);
            message.setChannel(Channel.META_HANDSHAKE);
            List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
            List<String> transportNames = new ArrayList<>(transports.size());
            for (ClientTransport transport : transports)
                transportNames.add(transport.getName());
            message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transportNames);
            message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);

            debug("Handshaking with extra fields {}, transport {}", bayeuxClientState.handshakeFields, bayeuxClientState.transport);
            bayeuxClientState.send(handshakeListener, message);
            return true;
        }
        return false;
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
        long start = System.nanoTime();
        List<State> waitForStates = new ArrayList<>();
        waitForStates.add(state);
        waitForStates.addAll(Arrays.asList(states));
        synchronized (this)
        {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            while (elapsed < waitMs)
            {
                // This check is needed to avoid that we return from waitFor() too early,
                // when the state has been set, but its effects (like notifying listeners)
                // are not completed yet (COMETD-212).
                // Transient states (like CONNECTING or DISCONNECTING) may "miss" the
                // wake up in this way:
                // * T1 goes in wait - releases lock
                // * T2 finishes update to CONNECTING - notifies lock
                // * T3 starts a state update to CONNECTED - releases lock
                // * T1 wakes up, takes lock, but sees update in progress, waits - releases lock
                // * T3 finishes update to CONNECTED - notifies lock
                // * T1 wakes up, takes lock, sees status == CONNECTED - CONNECTING has been "missed"
                // To avoid this, we use State.implies()
                if (stateUpdaters == 0)
                {
                    State currentState = getState();
                    for (State s : waitForStates)
                    {
                        if (currentState.implies(s))
                            return true;
                    }
                }

                try
                {
                    wait(waitMs - elapsed);
                }
                catch (InterruptedException x)
                {
                    Thread.currentThread().interrupt();
                    break;
                }

                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }
            return false;
        }
    }

    protected boolean sendConnect()
    {
        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        if (isHandshook(bayeuxClientState))
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_CONNECT);
            message.put(Message.CONNECTION_TYPE_FIELD, bayeuxClientState.transport.getName());
            if (isConnecting(bayeuxClientState) || bayeuxClientState.type == State.UNCONNECTED)
            {
                // First connect after handshake or after failure, add advice
                message.getAdvice(true).put("timeout", 0);
            }
            debug("Connecting, transport {}", bayeuxClientState.transport);
            bayeuxClientState.send(connectListener, message);
            return true;
        }
        return false;
    }

    protected ChannelId newChannelId(String channelId)
    {
        // Save some parsing by checking if there is already one
        AbstractSessionChannel channel = getChannels().get(channelId);
        return channel == null ? new ChannelId(channelId) : channel.getChannelId();
    }

    protected AbstractSessionChannel newChannel(ChannelId channelId)
    {
        return new BayeuxClientChannel(channelId);
    }

    protected void sendBatch()
    {
        if (canSend())
        {
            Message.Mutable[] messages = takeMessages();
            if (messages.length > 0)
                sendMessages(messages);
        }
    }

    protected boolean sendMessages(Message.Mutable... messages)
    {
        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        if (isConnecting(bayeuxClientState) || isConnected(bayeuxClientState))
        {
            bayeuxClientState.send(publishListener, messages);
            return true;
        }
        else
        {
            failMessages(null, messages);
            return false;
        }
    }

    private Message.Mutable[] takeMessages()
    {
        // Multiple threads can call this method concurrently (for example
        // a batched publish() is executed exactly when a message arrives
        // and a listener also performs a batched publish() in response to
        // the message).
        // The queue must be drained atomically, otherwise we risk that the
        // same message is drained twice.

        Message.Mutable[] messages;
        synchronized (messageQueue)
        {
            messages = messageQueue.toArray(new Message.Mutable[messageQueue.size()]);
            messageQueue.clear();
        }
        return messages;
    }

    /**
     * @see #disconnect(long)
     */
    public void disconnect()
    {
        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                if (isConnecting(oldState) || isConnected(oldState))
                    return new DisconnectingState(oldState.transport, oldState.clientId);
                else if (isDisconnecting(oldState))
                    return new DisconnectingState(oldState.transport, oldState.clientId);
                else
                    return new DisconnectedState(oldState.transport);
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
        if (isDisconnected(bayeuxClientState.get()))
            return true;

        final CountDownLatch latch = new CountDownLatch(1);
        ClientSessionChannel.MessageListener lastConnectListener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                final Map<String, Object> advice = message.getAdvice();
                if (advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)))
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

        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        if (bayeuxClientState.type == State.DISCONNECTED)
            if (bayeuxClientState.transport != null)
                bayeuxClientState.transport.terminate();

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
        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                return new AbortedState(oldState.transport);
            }
        });
    }

    protected void processHandshake(final Message.Mutable handshake)
    {
        debug("Processing meta handshake {}", handshake);
        if (handshake.isSuccessful())
        {
            Object field = handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD);
            Object[] serverTransports = field instanceof List ? ((List)field).toArray() : (Object[])field;
            List<ClientTransport> negotiatedTransports = transportRegistry.negotiate(serverTransports, BAYEUX_VERSION);
            final ClientTransport newTransport = negotiatedTransports.isEmpty() ? null : negotiatedTransports.get(0);
            if (newTransport == null)
            {
                // Signal the failure
                String error = "405:c" +
                        getAllowedTransports() +
                        ",s" +
                        Arrays.toString(serverTransports) +
                        ":no transport";

                handshake.setSuccessful(false);
                handshake.put(Message.ERROR_FIELD, error);
                // TODO: also update the advice with reconnect=none for listeners ?

                updateBayeuxClientState(new BayeuxClientStateUpdater()
                {
                    public BayeuxClientState create(BayeuxClientState oldState)
                    {
                        return new DisconnectedState(oldState.transport);
                    }

                    @Override
                    public void postCreate()
                    {
                        receive(handshake);
                    }
                });
            }
            else
            {
                updateBayeuxClientState(new BayeuxClientStateUpdater()
                {
                    public BayeuxClientState create(BayeuxClientState oldState)
                    {
                        if (newTransport != oldState.transport)
                        {
                            oldState.transport.reset();
                            newTransport.init();
                        }
                        String action = getAdviceAction(handshake.getAdvice(), Message.RECONNECT_RETRY_VALUE);
                        if (Message.RECONNECT_RETRY_VALUE.equals(action))
                            return new ConnectingState(oldState.handshakeFields, handshake.getAdvice(), newTransport, handshake.getClientId());
                        else if (Message.RECONNECT_NONE_VALUE.equals(action))
                            return new DisconnectedState(oldState.transport);
                        return null;
                    }

                    @Override
                    public void postCreate()
                    {
                        receive(handshake);
                    }
                });
            }
        }
        else
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    String action = getAdviceAction(handshake.getAdvice(), Message.RECONNECT_HANDSHAKE_VALUE);
                    if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action) || Message.RECONNECT_RETRY_VALUE.equals(action))
                        return new RehandshakingState(oldState.handshakeFields, oldState.transport, oldState.nextBackoff());
                    else if (Message.RECONNECT_NONE_VALUE.equals(action))
                        return new DisconnectedState(oldState.transport);
                    return null;
                }

                @Override
                public void postCreate()
                {
                    receive(handshake);
                }
            });
        }
    }

    protected void processConnect(final Message.Mutable connect)
    {
        // TODO: Split "connected" state into connectSent+connectReceived ?
        // It may happen that the server replies to the meta connect with a delay
        // that exceeds the maxNetworkTimeout (for example because the server is
        // busy and the meta connect reply thread is starved).
        // In this case, it is possible that we issue 2 concurrent connects, one
        // for the response arrived late, and one from the unconnected state.
        // We should avoid this, although it is a very rare case.

        debug("Processing meta connect {}", connect);
        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                Map<String, Object> advice = connect.getAdvice();
                if (advice == null)
                    advice = oldState.advice;

                String action = getAdviceAction(advice, Message.RECONNECT_RETRY_VALUE);
                if (connect.isSuccessful())
                {
                    if (Message.RECONNECT_RETRY_VALUE.equals(action))
                        return new ConnectedState(oldState.handshakeFields, advice, oldState.transport, oldState.clientId);
                    else if (Message.RECONNECT_NONE_VALUE.equals(action))
                        // This case happens when the connect reply arrives after a disconnect
                        // We do not go into a disconnected state to allow normal processing of the disconnect reply
                        return new DisconnectingState(oldState.transport, oldState.clientId);
                }
                else
                {
                    if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
                        return new RehandshakingState(oldState.handshakeFields, oldState.transport, 0);
                    else if (Message.RECONNECT_RETRY_VALUE.equals(action))
                        return new UnconnectedState(oldState.handshakeFields, advice, oldState.transport, oldState.clientId, oldState.nextBackoff());
                    else if (Message.RECONNECT_NONE_VALUE.equals(action))
                        return new DisconnectedState(oldState.transport);
                }
                return null;
            }

            @Override
            public void postCreate()
            {
                receive(connect);
            }
        });
    }

    protected void processDisconnect(final Message.Mutable disconnect)
    {
        debug("Processing meta disconnect {}", disconnect);

        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                return new DisconnectedState(oldState.transport);
            }

            @Override
            public void postCreate()
            {
                receive(disconnect);
            }
        });
    }

    protected void processMessage(Message.Mutable message)
    {
        debug("Processing message {}", message);
        receive(message);
    }

    private String getAdviceAction(Map<String, Object> advice, String defaultResult)
    {
        String action = defaultResult;
        if (advice != null && advice.containsKey(Message.RECONNECT_FIELD))
            action = (String)advice.get(Message.RECONNECT_FIELD);
        return action;
    }

    protected boolean scheduleHandshake(long interval, long backoff)
    {
        return scheduleAction(new Runnable()
        {
            public void run()
            {
                sendHandshake();
            }
        }, interval, backoff);
    }

    protected boolean scheduleConnect(long interval, long backoff)
    {
        return scheduleAction(new Runnable()
        {
            public void run()
            {
                sendConnect();
            }
        }, interval, backoff);
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
        debug("Could not schedule action {} to scheduler {}", action, scheduler);
        return false;
    }

    public List<String> getAllowedTransports()
    {
        return transportRegistry.getAllowedTransports();
    }

    public Set<String> getKnownTransportNames()
    {
        return transportRegistry.getKnownTransports();
    }

    public ClientTransport getTransport(String transport)
    {
        return transportRegistry.getTransport(transport);
    }

    public ClientTransport getTransport()
    {
        BayeuxClientState bayeuxClientState = this.bayeuxClientState.get();
        return bayeuxClientState == null ? null : bayeuxClientState.transport;
    }

    /**
     * @param debug whether the debug level for logging should be enabled
     * @see #isDebugEnabled()
     */
    public void setDebugEnabled(boolean debug)
    {
        this.debug = debug;
    }

    /**
     * @return whether the debug level for logging is enabled
     * @see #setDebugEnabled(boolean)
     */
    public boolean isDebugEnabled()
    {
        return debug;
    }

    protected void debug(String message, Object... args)
    {
        if (isDebugEnabled())
            logger.info(message, args);
        else
            logger.debug(message, args);
    }

    protected void initialize()
    {
        Long backoffIncrement = (Long)getOption(BACKOFF_INCREMENT_OPTION);
        if (backoffIncrement == null || backoffIncrement <= 0)
            backoffIncrement = 1000L;
        this.backoffIncrement = backoffIncrement;

        Long maxBackoff = (Long)getOption(MAX_BACKOFF_OPTION);
        if (maxBackoff == null || maxBackoff <= 0)
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
        Message.Mutable[] messages = takeMessages();
        failMessages(null, messages);

        cookieProvider.clear();

        if (shutdownScheduler)
        {
            shutdownScheduler = false;
            scheduler.shutdownNow();
            // TODO: await termination ?
            scheduler = null;
        }
    }

    public Object getOption(String qualifiedName)
    {
        return options.get(qualifiedName);
    }

    public void setOption(String qualifiedName, Object value)
    {
        options.put(qualifiedName, value);
    }

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

    protected Message.Mutable newMessage()
    {
        return new HashMapMessage();
    }

    protected void enqueueSend(Message.Mutable message)
    {
        if (canSend())
        {
            boolean sent = sendMessages(message);
            debug("{} message {}", sent ? "Sent" : "Failed", message);
        }
        else
        {
            synchronized (messageQueue)
            {
                messageQueue.add(message);
            }
            debug("Enqueued message {} (batching: {})", message, isBatching());
        }
    }

    private boolean canSend()
    {
        return !isBatching() && !isHandshaking(bayeuxClientState.get());
    }

    protected void failMessages(Throwable x, Message... messages)
    {
        for (Message message : messages)
        {
            Message.Mutable failed = newMessage();
            failed.setId(message.getId());
            failed.setSuccessful(false);
            failed.setChannel(message.getChannel());
            failed.put("message", message);
            if (x != null)
                failed.put("exception", x);
            failed.put(PUBLISH_CALLBACK_KEY, message.remove(PUBLISH_CALLBACK_KEY));
            receive(failed);
        }
    }

    @Override
    protected void notifyListeners(Message.Mutable message)
    {
        if (message.isPublishReply())
        {
            String messageId = message.getId();
            ClientSessionChannel.MessageListener listener = messageId == null ?
                    (ClientSessionChannel.MessageListener)message.remove(PUBLISH_CALLBACK_KEY) :
                    publishCallbacks.remove(messageId);
            if (listener != null)
                notifyListener(listener, message);
        }
        super.notifyListeners(message);
    }

    /**
     * <p>Callback method invoked when the given messages have hit the network towards the Bayeux server.</p>
     * <p>The messages may not be modified, and any modification will be useless because the message have
     * already been sent.</p>
     *
     * @param messages the messages sent
     */
    public void onSending(Message[] messages)
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
     * <p>The default implementation logs the failure at INFO level.</p>
     *
     * @param x        the exception that caused the failure
     * @param messages the messages being sent
     */
    public void onFailure(Throwable x, Message[] messages)
    {
        logger.info("Messages failed " + Arrays.toString(messages), x);
    }

    private void updateBayeuxClientState(BayeuxClientStateUpdater updater)
    {
        // Increase how many threads are updating the state.
        // This is needed so that in waitFor() we can check
        // the state being sure that nobody is updating it.
        synchronized (this)
        {
            ++stateUpdaters;
        }

        // State update is non-blocking
        try
        {
            BayeuxClientState newState = null;
            BayeuxClientState oldState = bayeuxClientState.get();
            boolean updated = false;
            while (!updated)
            {
                newState = updater.create(oldState);
                if (newState == null)
                    throw new IllegalStateException();

                if (!oldState.isUpdateableTo(newState))
                {
                    debug("State not updateable: {} -> {}", oldState, newState);
                    break;
                }

                updated = bayeuxClientState.compareAndSet(oldState, newState);
                debug("State update: {} -> {}{}", oldState, newState, updated ? "" : " failed (concurrent update)");
                if (!updated)
                    oldState = bayeuxClientState.get();
            }

            updater.postCreate();

            if (updated)
            {
                if (!oldState.getType().equals(newState.getType()))
                    newState.enter(oldState.getType());

                newState.execute();
            }
        }
        finally
        {
            // Notify threads waiting in waitFor()
            synchronized (this)
            {
                --stateUpdaters;
                if (stateUpdaters == 0)
                    notifyAll();
            }
        }
    }

    public String dump()
    {
        StringBuilder b = new StringBuilder();
        dump(b, "");
        return b.toString();
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
         * State assumed when the connect is being sent for the first time
         */
        CONNECTING(HANDSHAKING),
        /**
         * State assumed when this {@link BayeuxClient} is connected to the Bayeux server
         */
        CONNECTED(HANDSHAKING, CONNECTING),
        /**
         * State assumed when the disconnect is being sent
         */
        DISCONNECTING,
        /**
         * State assumed before the handshake and when the disconnect is completed
         */
        DISCONNECTED(DISCONNECTING);

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
    }

    private class PublishTransportListener implements TransportListener
    {
        public void onSending(Message[] messages)
        {
            BayeuxClient.this.onSending(messages);
        }

        public void onMessages(List<Message.Mutable> messages)
        {
            BayeuxClient.this.onMessages(messages);
            for (Message.Mutable message : messages)
                processMessage(message);
        }

        protected void processMessage(Message.Mutable message)
        {
            BayeuxClient.this.processMessage(message);
        }

        public void onFailure(Throwable failure, Message[] messages)
        {
            BayeuxClient.this.onFailure(failure, messages);
            failMessages(failure, messages);
        }
    }

    private class HandshakeTransportListener extends PublishTransportListener
    {
        public void onFailure(Throwable failure, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    List<ClientTransport> transports = transportRegistry.negotiate(getAllowedTransports().toArray(), BAYEUX_VERSION);
                    if (transports.isEmpty())
                    {
                        return new DisconnectedState(oldState.transport);
                    }
                    else
                    {
                        ClientTransport newTransport = transports.get(0);
                        if (newTransport != oldState.transport)
                        {
                            oldState.transport.reset();
                            newTransport.init();
                        }
                        return new RehandshakingState(oldState.handshakeFields, newTransport, oldState.nextBackoff());
                    }
                }
            });
            super.onFailure(failure, messages);
        }

        @Override
        protected void processMessage(Message.Mutable message)
        {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                processHandshake(message);
            else
                super.processMessage(message);
        }
    }

    private class ConnectTransportListener extends PublishTransportListener
    {
        @Override
        public void onFailure(Throwable failure, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    return new UnconnectedState(oldState.handshakeFields, oldState.advice, oldState.transport, oldState.clientId, oldState.nextBackoff());
                }
            });
            super.onFailure(failure, messages);
        }

        @Override
        protected void processMessage(Message.Mutable message)
        {
            if (Channel.META_CONNECT.equals(message.getChannel()))
                processConnect(message);
            else
                super.processMessage(message);
        }
    }

    private class DisconnectTransportListener extends PublishTransportListener
    {
        @Override
        public void onFailure(Throwable failure, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    return new DisconnectedState(oldState.transport);
                }
            });
            super.onFailure(failure, messages);
        }

        @Override
        protected void processMessage(Message.Mutable message)
        {
            if (Channel.META_DISCONNECT.equals(message.getChannel()))
                processDisconnect(message);
            else
                super.processMessage(message);
        }
    }

    protected class BayeuxClientChannel extends AbstractSessionChannel
    {
        protected BayeuxClientChannel(ChannelId channelId)
        {
            super(channelId);
        }

        public ClientSession getSession()
        {
            throwIfReleased();
            return BayeuxClient.this;
        }

        public void publish(Object data, MessageListener listener)
        {
            throwIfReleased();
            Message.Mutable message = newMessage();
            message.setChannel(getId());
            message.setData(data);
            if (listener != null)
                message.put(PUBLISH_CALLBACK_KEY, listener);
            enqueueSend(message);
        }

        protected void sendSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            enqueueSend(message);
        }

        protected void sendUnSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            enqueueSend(message);
        }
    }

    private abstract class BayeuxClientStateUpdater
    {
        public abstract BayeuxClientState create(BayeuxClientState oldState);

        public void postCreate()
        {
        }
    }

    private abstract class BayeuxClientState
    {
        protected final State type;
        protected final Map<String, Object> handshakeFields;
        protected final Map<String, Object> advice;
        protected final ClientTransport transport;
        protected final String clientId;
        protected final long backoff;

        private BayeuxClientState(State type,
                                  Map<String, Object> handshakeFields,
                                  Map<String, Object> advice,
                                  ClientTransport transport,
                                  String clientId,
                                  long backoff)
        {
            this.type = type;
            this.handshakeFields = handshakeFields;
            this.advice = advice;
            this.transport = transport;
            this.clientId = clientId;
            this.backoff = backoff;
        }

        protected long getInterval()
        {
            long result = 0;
            if (advice != null && advice.containsKey(Message.INTERVAL_FIELD))
                result = ((Number)advice.get(Message.INTERVAL_FIELD)).longValue();
            return result;
        }

        protected void send(TransportListener listener, Message.Mutable... messages)
        {
            // Use ArrayList because Arrays.asList() does not support Iterator.remove()
            List<Message.Mutable> messageList = new ArrayList<>(Arrays.asList(messages));
            for (Iterator<Message.Mutable> iterator = messageList.iterator(); iterator.hasNext();)
            {
                Message.Mutable message = iterator.next();

                String messageId = newMessageId();
                message.setId(messageId);

                if (clientId != null)
                    message.setClientId(clientId);

                // Remove the publish callback before calling the extensions
                ClientSessionChannel.MessageListener callback = (ClientSessionChannel.MessageListener)message.remove(PUBLISH_CALLBACK_KEY);

                if (extendSend(message))
                {
                    // Extensions may have modified the messageId, but we need to own
                    // the messageId in case of meta messages to link request/response
                    // in non request/response transports such as websocket
                    message.setId(messageId);
                    if (callback != null)
                        publishCallbacks.put(messageId, callback);
                }
                else
                {
                    iterator.remove();
                }
            }
            if (!messageList.isEmpty())
            {
                debug("Sending messages {}", messageList);
                transport.send(listener, messageList.toArray(new Message.Mutable[messageList.size()]));
            }
        }

        private long nextBackoff()
        {
            return Math.min(backoff + getBackoffIncrement(), getMaxBackoff());
        }

        protected abstract boolean isUpdateableTo(BayeuxClientState newState);

        /**
         * <p>Callback invoked when the state changed from the given {@code oldState}
         * to this state (and only when the two states are different).</p>
         *
         * @param oldState the previous state
         * @see #execute()
         */
        protected void enter(State oldState)
        {
        }

        /**
         * <p>Callback invoked when this state becomes the new state, even if the
         * previous state was equal to this state.</p>
         *
         * @see #enter(State)
         */
        protected abstract void execute();

        public State getType()
        {
            return type;
        }

        @Override
        public String toString()
        {
            return type.toString();
        }
    }

    private class DisconnectedState extends BayeuxClientState
    {
        private DisconnectedState(ClientTransport transport)
        {
            super(State.DISCONNECTED, null, null, transport, null, 0);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.HANDSHAKING;
        }

        @Override
        protected void execute()
        {
            transport.reset();
            terminate();
        }
    }

    private class AbortedState extends DisconnectedState
    {
        private AbortedState(ClientTransport transport)
        {
            super(transport);
        }

        @Override
        protected void execute()
        {
            transport.abort();
            super.execute();
        }
    }

    private class HandshakingState extends BayeuxClientState
    {
        private HandshakingState(Map<String, Object> handshakeFields, ClientTransport transport)
        {
            super(State.HANDSHAKING, handshakeFields, null, transport, null, 0);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.CONNECTING ||
                    newState.type == State.REHANDSHAKING ||
                    newState.type == State.DISCONNECTED;
        }

        @Override
        protected void enter(State oldState)
        {
            // Always reset the subscriptions when a handshake has been requested.
            resetSubscriptions();
        }

        @Override
        protected void execute()
        {
            // The state could change between now and when sendHandshake() runs;
            // in this case the handshake message will not be sent and will not
            // be failed, because most probably the client has been disconnected.
            sendHandshake();
        }
    }

    private class RehandshakingState extends BayeuxClientState
    {
        public RehandshakingState(Map<String, Object> handshakeFields, ClientTransport transport, long backoff)
        {
            super(State.REHANDSHAKING, handshakeFields, null, transport, null, backoff);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.CONNECTING ||
                    newState.type == State.REHANDSHAKING ||
                    newState.type == State.DISCONNECTED;
        }

        @Override
        protected void enter(State oldState)
        {
            // Reset the subscriptions if this is not a failure from a requested handshake.
            // Subscriptions may be queued after requested handshakes.
            if (oldState != State.HANDSHAKING)
                resetSubscriptions();
        }

        @Override
        protected void execute()
        {
            scheduleHandshake(getInterval(), backoff);
        }
    }

    private class ConnectingState extends BayeuxClientState
    {
        private ConnectingState(Map<String, Object> handshakeFields, Map<String, Object> advice, ClientTransport transport, String clientId)
        {
            super(State.CONNECTING, handshakeFields, advice, transport, clientId, 0);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.CONNECTED ||
                    newState.type == State.UNCONNECTED ||
                    newState.type == State.REHANDSHAKING ||
                    newState.type == State.DISCONNECTING ||
                    newState.type == State.DISCONNECTED;
        }

        @Override
        protected void execute()
        {
            // Send the messages that may have queued up before the handshake completed
            sendBatch();
            scheduleConnect(getInterval(), backoff);
        }
    }

    private class ConnectedState extends BayeuxClientState
    {
        private ConnectedState(Map<String, Object> handshakeFields, Map<String, Object> advice, ClientTransport transport, String clientId)
        {
            super(State.CONNECTED, handshakeFields, advice, transport, clientId, 0);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.CONNECTED ||
                    newState.type == State.UNCONNECTED ||
                    newState.type == State.REHANDSHAKING ||
                    newState.type == State.DISCONNECTING ||
                    newState.type == State.DISCONNECTED;
        }

        @Override
        protected void execute()
        {
            scheduleConnect(getInterval(), backoff);
        }
    }

    private class UnconnectedState extends BayeuxClientState
    {
        private UnconnectedState(Map<String, Object> handshakeFields, Map<String, Object> advice, ClientTransport transport, String clientId, long backoff)
        {
            super(State.UNCONNECTED, handshakeFields, advice, transport, clientId, backoff);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.CONNECTED ||
                    newState.type == State.UNCONNECTED ||
                    newState.type == State.REHANDSHAKING ||
                    newState.type == State.DISCONNECTED;
        }

        @Override
        protected void execute()
        {
            scheduleConnect(getInterval(), backoff);
        }
    }

    private class DisconnectingState extends BayeuxClientState
    {
        private DisconnectingState(ClientTransport transport, String clientId)
        {
            super(State.DISCONNECTING, null, null, transport, clientId, 0);
        }

        @Override
        protected boolean isUpdateableTo(BayeuxClientState newState)
        {
            return newState.type == State.DISCONNECTED;
        }

        @Override
        protected void execute()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_DISCONNECT);
            send(disconnectListener, message);
        }
    }
}
