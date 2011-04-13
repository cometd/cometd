package org.cometd.client;

import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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

    private final Logger logger = Log.getLogger(getClass().getName() + "@" + System.identityHashCode(this));
    private final TransportRegistry transportRegistry = new TransportRegistry();
    private final Map<String, Object> options = new ConcurrentHashMap<String, Object>();
    private final AtomicReference<BayeuxClientState> bayeuxClientState = new AtomicReference<BayeuxClientState>();
    private final Queue<Message.Mutable> messageQueue = new ConcurrentLinkedQueue<Message.Mutable>();
    private final HttpClientTransport.CookieProvider cookieProvider = new HttpClientTransport.StandardCookieProvider();
    private final TransportListener handshakeListener = new HandshakeTransportListener();
    private final TransportListener connectListener = new ConnectTransportListener();
    private final TransportListener disconnectListener = new DisconnectTransportListener();
    private final TransportListener publishListener = new PublishTransportListener();
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean shutdownScheduler;
    private volatile long backoffIncrement;
    private volatile long maxBackoff;
    private int stateUpdateInProgress;

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

    public boolean isConnected()
    {
        return isConnected(bayeuxClientState.get());
    }

    private boolean isConnected(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.CONNECTED;
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

    /**
     * @return whether this {@link BayeuxClient} is disconnecting or disconnected
     */
    public boolean isDisconnected()
    {
        return isDisconnected(bayeuxClientState.get());
    }

    private boolean isDisconnected(BayeuxClientState bayeuxClientState)
    {
        return bayeuxClientState.type == State.DISCONNECTING ||
                bayeuxClientState.type == State.DISCONNECTED;
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

        final List<String> allowedTransports = getAllowedTransports();
        // Pick the first transport for the handshake, it will renegotiate if not right
        final ClientTransport initialTransport = transportRegistry.getTransport(allowedTransports.get(0));
        initialTransport.init();
        logger.debug("Using initial transport {} from {}", initialTransport.getName(), allowedTransports);

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
        waitFor(waitMs, State.CONNECTING, State.DISCONNECTED);
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
            message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, getAllowedTransports());
            message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
            if (message.getId() == null)
                message.setId(newMessageId());

            logger.debug("Handshaking with extra fields {}, transport {}", bayeuxClientState.handshakeFields, bayeuxClientState.transport);
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
        List<State> waitForStates = new ArrayList<State>();
        waitForStates.add(state);
        waitForStates.addAll(Arrays.asList(states));
        synchronized (this)
        {
            while (System.nanoTime() - start <= TimeUnit.MILLISECONDS.toNanos(waitMs))
            {
                if (stateUpdateInProgress == 0)
                {
                    State currentState = getState();
                    for (State s : waitForStates)
                    {
                        if (s == currentState)
                            return true;
                    }
                }
                try
                {
                    wait(waitMs);
                }
                catch (InterruptedException x)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
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
            if (bayeuxClientState.type == State.CONNECTING || bayeuxClientState.type == State.UNCONNECTED)
            {
                // First connect after handshake or after failure, add advice
                message.getAdvice(true).put("timeout", 0);
            }
            logger.debug("Connecting, transport {}", bayeuxClientState.transport);
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
        if (bayeuxClientState.type == State.CONNECTING || isConnected(bayeuxClientState))
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
        Queue<Message.Mutable> queue = new LinkedList<Message.Mutable>(messageQueue);
        // Do not call messageQueue.clear(), as it can contain new messages added concurrently
        messageQueue.removeAll(queue);
        return queue.toArray(new Message.Mutable[queue.size()]);
    }

    public void disconnect()
    {
        updateBayeuxClientState(new BayeuxClientStateUpdater()
        {
            public BayeuxClientState create(BayeuxClientState oldState)
            {
                if (isConnected(oldState))
                    return new DisconnectingState(oldState.transport, oldState.clientId);
                else
                    return new DisconnectedState(oldState.transport);
            }
        });
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
        logger.debug("Processing handshake {}", handshake);
        if (handshake.isSuccessful())
        {
            Object[] serverTransports = (Object[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD);
            List<ClientTransport> negotiatedTransports = transportRegistry.negotiate(serverTransports, BayeuxClient.BAYEUX_VERSION);
            final ClientTransport newTransport = negotiatedTransports.isEmpty() ? null : negotiatedTransports.get(0);
            if (newTransport == null)
            {
                // Signal the failure
                String error = "405:c" +
                        transportRegistry.getAllowedTransports() +
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
        logger.debug("Processing connect {}", connect);
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
        logger.debug("Processing disconnect {}", disconnect);

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
        logger.debug("Processing message {}", message);
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
                logger.debug(x);
            }
        }
        logger.debug("Could not schedule action {} to scheduler {}", action, scheduler);
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

    public Transport getTransport(String transport)
    {
        return transportRegistry.getTransport(transport);
    }

    /**
     * @param debug whether the debug level for logging should be enabled
     * @see #isDebugEnabled()
     */
    public void setDebugEnabled(boolean debug)
    {
        logger.setDebugEnabled(debug);
    }

    /**
     * @return whether the debug level for logging is enabled
     * @see #setDebugEnabled(boolean)
     */
    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
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
            logger.debug("{} message {}", sent ? "Sent" : "Failed", message);
        }
        else
        {
            messageQueue.offer(message);
            logger.debug("Enqueued message {} (batching: {})", message, isBatching());
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
            failed.put("message", messages);
            if (x != null)
                failed.put("exception", x);
            receive(failed);
        }
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
        logger.info(x);
    }

    private void updateBayeuxClientState(BayeuxClientStateUpdater updater)
    {
        synchronized (this)
        {
            ++stateUpdateInProgress;
        }
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
                    logger.debug("State not updateable : {} -> {}", oldState, newState);
                    break;
                }

                updated = bayeuxClientState.compareAndSet(oldState, newState);
                logger.debug("State update" + (updated ? "" : " failed (concurrent update)") + ": {} -> {}", oldState, newState);
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
                --stateUpdateInProgress;
                if (stateUpdateInProgress == 0)
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
        CONNECTING,
        /**
         * State assumed when this {@link BayeuxClient} is connected to the Bayeux server
         */
        CONNECTED,
        /**
         * State assumed when the disconnect is being sent
         */
        DISCONNECTING,
        /**
         * State assumed before the handshake and when the disconnect is completed
         */
        DISCONNECTED
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

        public void onConnectException(Throwable x, Message[] messages)
        {
            onFailure(x, messages);
        }

        public void onException(Throwable x, Message[] messages)
        {
            onFailure(x, messages);
        }

        public void onExpire(Message[] messages)
        {
            onFailure(new TimeoutException("expired"), messages);
        }

        public void onProtocolError(String info, Message[] messages)
        {
            onFailure(new ProtocolException(info), messages);
        }

        protected void processMessage(Message.Mutable message)
        {
            BayeuxClient.this.processMessage(message);
        }

        protected void onFailure(Throwable x, Message[] messages)
        {
            BayeuxClient.this.onFailure(x, messages);
            failMessages(x, messages);
        }
    }

    private class HandshakeTransportListener extends PublishTransportListener
    {
        protected void onFailure(Throwable x, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    return new RehandshakingState(oldState.handshakeFields, oldState.transport, oldState.nextBackoff());
                }
            });
            super.onFailure(x, messages);
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
        protected void onFailure(Throwable x, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    return new UnconnectedState(oldState.handshakeFields, oldState.advice, oldState.transport, oldState.clientId, oldState.nextBackoff());
                }
            });
            super.onFailure(x, messages);
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
        protected void onFailure(Throwable x, Message[] messages)
        {
            updateBayeuxClientState(new BayeuxClientStateUpdater()
            {
                public BayeuxClientState create(BayeuxClientState oldState)
                {
                    return new DisconnectedState(oldState.transport);
                }
            });
            super.onFailure(x, messages);
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
            return BayeuxClient.this;
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

        public void publish(Object data)
        {
            publish(data, null);
        }

        public void publish(Object data, String messageId)
        {
            Message.Mutable message = newMessage();
            message.setChannel(getId());
            message.setData(data);
            if (messageId != null)
                message.setId(String.valueOf(messageId));
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
            List<Message.Mutable> messageList = Arrays.asList(messages);
            for (Iterator<Message.Mutable> iterator = messageList.iterator(); iterator.hasNext();)
            {
                Message.Mutable message = iterator.next();

                if (message.getId() == null)
                    message.setId(newMessageId());
                if (clientId != null)
                    message.setClientId(clientId);

                if (!extendSend(message))
                    iterator.remove();
            }
            if (!messageList.isEmpty())
            {
                logger.debug("Sending messages {}", messageList);
                transport.send(listener, messageList.toArray(new Message.Mutable[messageList.size()]));
            }
        }

        private long nextBackoff()
        {
            return Math.min(backoff + getBackoffIncrement(), getMaxBackoff());
        }

        protected abstract boolean isUpdateableTo(BayeuxClientState newState);

        /**
         * Enter a new state.
         * Called only if a new accepted state has a different type to the old state.
         *
         * @param oldState
         */
        protected void enter(State oldState)
        {
        }

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
            return newState.type == State.REHANDSHAKING ||
                    newState.type == State.CONNECTING ||
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
            switch (oldState)
            {
                case HANDSHAKING:
                    break;
                default:
                    // Reset subscriptions if not queued after initial handshake
                    resetSubscriptions();
            }
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
