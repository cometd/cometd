package org.cometd.client;

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

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.cometd.common.AbstractClientSession;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxClient extends AbstractClientSession implements Bayeux, TransportListener
{
    public static final String BACKOFF_INCREMENT_OPTION = "backoffIncrement";
    public static final String MAX_BACKOFF_OPTION = "maxBackoff";

    public static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = Log.getLogger(getClass().getName());
    private final TransportRegistry transportRegistry = new TransportRegistry();
    private final Map<String,Object> options = new ConcurrentHashMap<String, Object>();
    private final Queue<Message.Mutable> messageQueue = new ConcurrentLinkedQueue<Message.Mutable>();
    private Map<String, ExpirableCookie> cookies = new ConcurrentHashMap<String, ExpirableCookie>();
    private final TransportListener listener = new Listener();
    private final HttpURI url;
    private volatile Map<String, Object> handshakeFields;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean shutdownScheduler;
    private volatile boolean handshakeBatch;
    private volatile ClientTransport transport;
    private volatile String clientId;
    private volatile Map<String, Object> advice;
    private volatile int backoffTries;
    private volatile long backoffIncrement;
    private volatile long maxBackoff;
    private volatile State state = State.UNCONNECTED;

    public BayeuxClient(String url, ClientTransport transport, ClientTransport... transports)
    {
        this(url, null, transport, transports);
    }

    public BayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport transport, ClientTransport... transports)
    {
        if (transport == null)
            throw new IllegalArgumentException("Transport cannot be null");

        this.url = new HttpURI(url);
        this.scheduler = scheduler;

        transportRegistry.add(transport);
        for (ClientTransport t : transports)
            transportRegistry.add(t);
    }

    public long getBackoffIncrement()
    {
        return backoffIncrement;
    }

    public long getMaxBackoff()
    {
        return maxBackoff;
    }

    public String getCookie(String name)
    {
        ExpirableCookie cookie = cookies.get(name);
        if (cookie != null)
        {
            if (cookie.isExpired())
            {
                cookies.remove(cookie.getName());
                cookie = null;
            }
        }
        return cookie == null ? null : cookie.getValue();
    }

    public void setCookie(String name, String value)
    {
        setCookie(name, value, -1);
    }

    public void setCookie(String name, String value, int maxAge)
    {
        long expirationTime = System.currentTimeMillis();
        if (maxAge < 0)
            expirationTime = -1L;
        else
            expirationTime += TimeUnit.SECONDS.toMillis(maxAge);
        ExpirableCookie expirableCookie = new ExpirableCookie(name, value, expirationTime);
        cookies.put(name, expirableCookie);
    }

    @Override
    public String getId()
    {
        return clientId;
    }

    @Override
    public boolean isConnected()
    {
        return state == State.CONNECTED;
    }
    
    @Override
    public boolean isHandshook()
    {
        return state == State.CONNECTED || state==State.CONNECTING;
    }

    protected State getState()
    {
        return state;
    }

    @Override
    public void handshake()
    {
        handshake(null);
    }

    @Override
    public void handshake(Map<String, Object> handshakeFields)
    {
        initialize();
        this.handshakeFields = handshakeFields;

        List<String> allowedTransport = getAllowedTransports();
        Message.Mutable message = newMessage();
        if (handshakeFields != null)
            message.putAll(handshakeFields);

        message.setChannel(Channel.META_HANDSHAKE);
        message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, allowedTransport);
        message.put(Message.VERSION_FIELD, BayeuxClient.BAYEUX_VERSION);
        message.setId(newMessageId());

        // Pick the first transport for the handshake, it will renegotiate if not right
        ClientTransport initialTransport = transportRegistry.getTransport(allowedTransport.get(0));
        updateTransport(initialTransport);
        updateState(State.HANDSHAKING);
        logger.debug("Handshaking with extra fields {}, transport {}", handshakeFields, initialTransport);
        handshakeBatch = true;
        send(message);
    }

    public State handshake(long waitMs)
    {
        return handshake(null, waitMs);
    }

    public State handshake(Map<String,Object> template, long waitMs)
    {
        handshake(template);
        waitFor(waitMs, State.CONNECTED, State.CONNECTING, State.DISCONNECTED, State.UNCONNECTED);
        return getState();
    }

    public boolean waitFor(long waitMs, State state, State... states)
    {
        long start = System.currentTimeMillis();
        List<State> waitForStates = new ArrayList<State>();
        waitForStates.add(state);
        waitForStates.addAll(Arrays.asList(states));
        synchronized (this)
        {
            while (System.currentTimeMillis() - start < waitMs)
            {
                State currentState = getState();
                for (State s : waitForStates)
                {
                    if (s == currentState)
                        return true;
                }

                try
                {
                    wait(waitMs);
                }
                catch(InterruptedException x)
                {
                    return false;
                }
            }

            State currentState = getState();
            for (State s : waitForStates)
            {
                if (s == currentState)
                    return true;
            }

            return false;
        }
    }

    protected void connect()
    {
        Message.Mutable message = newMessage();
        message.setChannel(Channel.META_CONNECT);
        message.put(Message.CONNECTION_TYPE_FIELD, transport.getName());
        updateState(State.CONNECTED);
        logger.debug("Connecting, transport {}", transport);
        send(message);
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
        if (handshakeBatch)
            return;

        Queue<Message.Mutable> queue = new LinkedList<Message.Mutable>(messageQueue);
        // Do not call messageQueue.clear(), as it can contain new messages added concurrently
        messageQueue.removeAll(queue);
        if (!queue.isEmpty())
        {
            logger.debug("Dequeued messages {}", queue);
            send(queue.toArray(new Message.Mutable[queue.size()]));
        }
    }

    @Override
    public void disconnect()
    {
        if (isConnected())
        {
            updateState(State.DISCONNECTING);
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_DISCONNECT);
            send(message);
        }
        else
        {
            terminate();
        }
    }

    @Override
    public void receive(Message message, Message.Mutable mutable)
    {
        logger.debug("Received message {} by {}", message, this);
        updateAdvice(message);

        String channelName = message.getChannel();

        if (Channel.META_HANDSHAKE.equals(channelName))
            processHandshake(message);
        else if (Channel.META_CONNECT.equals(channelName))
            processConnect(message);
        else if (Channel.META_DISCONNECT.equals(channelName))
            processDisconnect(message);

        super.receive(message, mutable);
    }

    protected Map<String, Object> getAdvice()
    {
        return advice;
    }

    private void updateAdvice(Message message)
    {
        Map<String, Object> advice = message.getAdvice();
        if (advice != null)
        {
            this.advice = advice;
            logger.debug("Updated advice to {}", advice);
        }
    }

    protected void followAdvice()
    {
        String action = Message.RECONNECT_RETRY_VALUE;
        long interval = 0L;
        Map<String, Object> advice = getAdvice();
        if (advice != null)
        {
            if (advice.containsKey(Message.RECONNECT_FIELD))
                action = (String)advice.get(Message.RECONNECT_FIELD);
            if (advice.containsKey(Message.INTERVAL_FIELD))
                interval = ((Number)advice.get(Message.INTERVAL_FIELD)).longValue();
        }

        if (Message.RECONNECT_NONE_VALUE.equals(action))
        {
            terminate();
            return;
        }

        if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
        {
            updateState(State.HANDSHAKING);
        }

        State state = getState();
        switch (state)
        {
            case HANDSHAKING:
                scheduleAction(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        handshake(handshakeFields);
                    }
                }, interval);
                break;
            case CONNECTING:
            case CONNECTED:
            case UNCONNECTED:
                scheduleAction(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        connect();
                    }
                }, interval);
                break;
            case DISCONNECTING:
            case DISCONNECTED:
                terminate();
                break;
            default:
                throw new IllegalStateException("Illegal state " + state);
        }
    }

    protected void processHandshake(Message handshake)
    {
        logger.debug("Processing handshake {}", handshake);
        if (handshake.isSuccessful())
        {
            resetBackoff();
            Object[] serverTransports = (Object[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD);
            List<ClientTransport> negotiatedTransports = transportRegistry.negotiate(serverTransports, BayeuxClient.BAYEUX_VERSION);
            ClientTransport newTransport = negotiatedTransports.isEmpty() ? null : negotiatedTransports.get(0);
            if (newTransport == null)
            {
                // TODO
                throw new UnsupportedOperationException();
            }
            else
            {
                clientId = handshake.getClientId();
                updateTransport(newTransport);
                updateState(State.CONNECTING);
                handshakeBatch = false;
                sendBatch();
            }
        }
        else
        {
            increaseBackoff();
        }
        followAdvice();
    }

    protected void processConnect(Message connect)
    {
        logger.debug("Processing connect {}", connect);
        if (!connect.isSuccessful())
        {
            updateState(State.UNCONNECTED);
            increaseBackoff();
        }
        followAdvice();
    }

    protected void processDisconnect(Message disconnect)
    {
        logger.debug("Processing disconnect {}", disconnect);
        terminate();
    }

    protected boolean scheduleAction(Runnable action, long interval)
    {
        // Prevent NPE in case of concurrent disconnect
        ScheduledExecutorService scheduler = this.scheduler;
        if (scheduler != null)
        {
            long backoff = calculateBackoff();
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
        return false;
    }

    private void increaseBackoff()
    {
        ++backoffTries;
    }

    private void resetBackoff()
    {
        backoffTries = 0;
    }

    private long calculateBackoff()
    {
        return Math.min(backoffTries * getBackoffIncrement(), getMaxBackoff());
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
    public Transport getTransport(String transport)
    {
        return transportRegistry.getTransport(transport);
    }

    protected void initialize()
    {
        Long backoffIncrement = (Long)getOption(BACKOFF_INCREMENT_OPTION);
        if (backoffIncrement == null)
            backoffIncrement = 1000L;
        this.backoffIncrement = backoffIncrement;

        Long maxBackoff = (Long)getOption(MAX_BACKOFF_OPTION);
        if (maxBackoff == null)
            maxBackoff = 30000L;
        this.maxBackoff = maxBackoff;

        resetBackoff();
        advice = null;
        handshakeBatch = false;
        messageQueue.clear();
        if (scheduler == null)
        {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            shutdownScheduler = true;
        }
    }

    protected void terminate()
    {
        updateState(State.DISCONNECTED);
        resetBackoff();
        advice = null;
        handshakeBatch = false;
        messageQueue.clear();
        if (shutdownScheduler)
        {
            shutdownScheduler = false;
            scheduler.shutdownNow();
            // TODO: await termination ?
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
    }

    @Override
    public Set<String> getOptionNames()
    {
        return options.keySet();
    }

    public Map<String,Object> getOptions()
    {
        return Collections.unmodifiableMap(options);
    }

    protected Message.Mutable newMessage()
    {
        if (transport!=null)
            return transport.newMessage();
        return new HashMapMessage();
    }

    protected void updateTransport(ClientTransport newTransport)
    {
        if (transport == newTransport)
            return;

        if (transport != null)
            transport.reset();

        newTransport.init(this, url);
        Transport oldTransport = transport;
        transport = newTransport;
        logger.debug("Updated transport: {} -> {}", oldTransport, newTransport);
    }

    protected void send(Message.Mutable... messages)
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

    protected void enqueueSend(Message.Mutable message)
    {
        boolean batching = isBatching();
        if (batching || handshakeBatch)
        {
            messageQueue.offer(message);
            logger.debug("Enqueued message {}, batching {}", message, batching);
        }
        else
        {
            send(message);
        }
    }

    private void updateState(State newState)
    {
        // TODO: will need notifications when adding waitForState()
        State oldState = state;
        state = newState;
        logger.debug("Updated state: {} -> {}", oldState, newState);
    }

    @Override
    public void onSending(Message[] messages)
    {
    }

    @Override
    public void onMessages(List<Message.Mutable> messages)
    {
    }

    @Override
    public void onConnectException(Throwable x)
    {
    }

    @Override
    public void onException(Throwable x)
    {
    }

    @Override
    public void onExpire()
    {
    }

    @Override
    public void onProtocolError(String info)
    {
    }

    /**
     * Customizes an Exchange. Called when an exchange is about to be sent to
     * allow Cookies and Credentials to be customized. Default implementation
     * sets any cookies
     */
    public void customize(HttpExchange exchange)
    {
        StringBuilder builder = null;
        for (String cookieName : cookies.keySet())
        {
            if (builder == null)
                builder = new StringBuilder();
            else
                builder.append("; ");

            // Expiration is handled by getCookie()
            String value = getCookie(cookieName);
            if (value != null)
            {
                builder.append(QuotedStringTokenizer.quote(cookieName));
                builder.append("=");
                builder.append(QuotedStringTokenizer.quote(value));
            }
        }

        if (builder != null)
            exchange.setRequestHeader(HttpHeaders.COOKIE, builder.toString());
    }

    @Override
    public String toString()
    {
        return super.toString() + ":" + url + ":" + getState();
    }

    public enum State
    {
        UNCONNECTED, HANDSHAKING, CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED
    }

    private class Listener implements TransportListener
    {
        @Override
        public void onSending(Message[] messages)
        {
            BayeuxClient.this.onSending(messages);
        }

        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            BayeuxClient.this.onMessages(messages);
            for (Message.Mutable message : messages)
                receive(message, message);
        }

        @Override
        public void onConnectException(Throwable x)
        {
            BayeuxClient.this.onConnectException(x);
            onFailure();
        }

        @Override
        public void onException(Throwable x)
        {
            BayeuxClient.this.onException(x);
            onFailure();
        }

        @Override
        public void onExpire()
        {
            BayeuxClient.this.onExpire();
            onFailure();
        }

        @Override
        public void onProtocolError(String info)
        {
            BayeuxClient.this.onProtocolError(info);
            onFailure();
        }

        private void onFailure()
        {
            if (getState() == State.CONNECTED)
                updateState(State.UNCONNECTED);
            increaseBackoff();
            followAdvice();
        }
    }

    private class BayeuxClientChannel extends AbstractSessionChannel
    {
        private BayeuxClientChannel(ChannelId channelId)
        {
            super(channelId);
        }

        @Override
        public ClientSession getSession()
        {
            return BayeuxClient.this;
        }

        @Override
        protected void sendSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_SUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            enqueueSend(message);
        }

        @Override
        protected void sendUnSubscribe()
        {
            Message.Mutable message = newMessage();
            message.setChannel(Channel.META_UNSUBSCRIBE);
            message.put(Message.SUBSCRIPTION_FIELD, getId());
            enqueueSend(message);
        }

        @Override
        public void publish(Object data)
        {
            publish(data, null);
        }

        @Override
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

    private static class ExpirableCookie
    {
        private final String name;
        private final String value;
        private final long expirationTime;

        private ExpirableCookie(String name, String value, long expirationTime)
        {
            this.name = name;
            this.value = value;
            this.expirationTime = expirationTime;
        }

        private boolean isExpired()
        {
            long expire = getExpirationTime();
            if (expire < 0) return false;
            return System.currentTimeMillis() >= expire;
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        public long getExpirationTime()
        {
            return expirationTime;
        }
    }
}
