/*
 * Copyright (c) 2011 the original author or authors.
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

package org.cometd.websocket.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;

public abstract class AbstractWebSocketTransport<S> extends HttpClientTransport implements MessageClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public final static String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public final static String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";
    public final static String STICKY_RECONNECT_OPTION = "stickyReconnect";

    private final Map<String, WebSocketExchange> _exchanges = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService _scheduler;
    private volatile boolean _shutdownScheduler;
    private volatile String _protocol = null;
    private volatile long _maxNetworkDelay;
    private volatile long _connectTimeout;
    private volatile long _idleTimeout;
    private volatile boolean _stickyReconnect;
    private volatile boolean _connected;
    private volatile boolean _disconnected;
    private volatile boolean _aborted;
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;

    protected AbstractWebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler)
    {
        super(NAME, url, options);
        _scheduler = scheduler;
        setOptionPrefix(PREFIX);
    }

    public void setMessageTransportListener(TransportListener listener)
    {
        _listener = listener;
    }

    @Override
    public void setURL(String url)
    {
        // Mangle the URL
        super.setURL(url.replaceFirst("^http", "ws"));
    }

    @Override
    public void init()
    {
        super.init();
        _exchanges.clear();
        _aborted = false;

        if (_scheduler == null)
        {
            _shutdownScheduler = true;
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(threads);
            scheduler.setRemoveOnCancelPolicy(true);
            _scheduler = scheduler;
        }

        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, 15000L);
        _connectTimeout = getOption(CONNECT_TIMEOUT_OPTION, 30000L);
        _idleTimeout = getOption(IDLE_TIMEOUT_OPTION, 60000L);
        _stickyReconnect = getOption(STICKY_RECONNECT_OPTION, true);
    }

    public String getProtocol()
    {
        return _protocol;
    }

    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    public long getMaxNetworkDelay()
    {
        return _maxNetworkDelay;
    }

    public long getConnectTimeout()
    {
        return _connectTimeout;
    }

    public boolean isStickyReconnect()
    {
        return _stickyReconnect;
    }

    @Override
    public void abort()
    {
        _aborted = true;
        disconnect("Aborted");
        shutdownScheduler();
    }

    public boolean isAborted()
    {
        return _aborted;
    }

    @Override
    public void terminate()
    {
        disconnect("Terminated");
        shutdownScheduler();
        super.terminate();
    }

    private void shutdownScheduler()
    {
        if (_shutdownScheduler)
        {
            _shutdownScheduler = false;
            _scheduler.shutdownNow();
            _scheduler = null;
        }
    }

    protected abstract void disconnect(String reason);

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        if (_aborted)
            throw new IllegalStateException("Aborted");

        // Mangle the URL
        String url = getURL();
        url = url.replaceFirst("^http", "ws");
        S session = connect(url, listener, messages);
        if (session == null)
            return;

        for (Message.Mutable message : messages)
            registerMessage(message, listener);

        String content = generateJSON(messages);

        // The onSending() callback must be invoked before the actual send
        // otherwise we may have a race condition where the response is so
        // fast that it arrives before the onSending() is called.
        logger.debug("Sending messages {}", content);
        listener.onSending(messages);

        send(session, content, listener, messages);
    }

    protected abstract S connect(String uri, TransportListener listener, Mutable[] messages);

    protected abstract void send(S session, String content, TransportListener listener, Message.Mutable... messages);

    protected void complete(Message.Mutable[] messages)
    {
        for (Message.Mutable message : messages)
            deregisterMessage(message);
    }

    protected void registerMessage(final Message.Mutable message, final TransportListener listener)
    {
        // Calculate max network delay
        long maxNetworkDelay = getMaxNetworkDelay();
        if (Channel.META_CONNECT.equals(message.getChannel()))
        {
            Map<String, Object> advice = message.getAdvice();
            if (advice == null)
                advice = _advice;
            if (advice != null)
            {
                Object timeout = advice.get("timeout");
                if (timeout instanceof Number)
                    maxNetworkDelay += ((Number)timeout).intValue();
                else if (timeout != null)
                    maxNetworkDelay += Integer.parseInt(timeout.toString());
            }
            _connected = true;
        }

        // Schedule a task to expire if the maxNetworkDelay elapses
        final long expiration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + maxNetworkDelay;
        ScheduledFuture<?> task = _scheduler.schedule(new Runnable()
        {
            public void run()
            {
                long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                long delay = now - expiration;
                if (delay > 5000) // TODO: make the max delay a parameter ?
                    logger.debug("Message {} expired {} ms too late", message, delay);

                // Notify only if we won the race to deregister the message
                WebSocketExchange exchange = deregisterMessage(message);
                if (exchange != null)
                    listener.onFailure(new TimeoutException("Exchange expired"), new Message[]{message});
            }
        }, maxNetworkDelay, TimeUnit.MILLISECONDS);

        // Register the exchange
        // Message responses must have the same messageId as the requests

        WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
        logger.debug("Registering {}", exchange);
        Object existing = _exchanges.put(message.getId(), exchange);
        // Paranoid check
        if (existing != null)
            throw new IllegalStateException();
    }

    protected WebSocketExchange deregisterMessage(Message message)
    {
        WebSocketExchange exchange = _exchanges.remove(message.getId());
        if (Channel.META_CONNECT.equals(message.getChannel()))
            _connected = false;
        else if (Channel.META_DISCONNECT.equals(message.getChannel()))
            _disconnected = true;

        logger.debug("Deregistering {} for message {}", exchange, message);

        if (exchange != null)
            exchange.task.cancel(false);

        return exchange;
    }

    private boolean isReply(Message message)
    {
        return message.isMeta() || message.isPublishReply();
    }

    protected void failMessages(Throwable cause)
    {
        List<WebSocketExchange> exchanges = new ArrayList<>(_exchanges.values());
        for (WebSocketExchange exchange : exchanges)
        {
            deregisterMessage(exchange.message);
            exchange.listener.onFailure(cause, new Message[]{exchange.message});
        }
    }

    protected void onMessages(List<Mutable> messages)
    {
        for (Mutable message : messages)
        {
            if (isReply(message))
            {
                // Remembering the advice must be done before we notify listeners
                // otherwise we risk that listeners send a connect message that does
                // not take into account the timeout to calculate the maxNetworkDelay
                if (Channel.META_CONNECT.equals(message.getChannel()) && message.isSuccessful())
                {
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null)
                    {
                        // Remember the advice so that we can properly calculate the max network delay
                        if (advice.get(Message.TIMEOUT_FIELD) != null)
                            _advice = advice;
                    }
                }

                WebSocketExchange exchange = deregisterMessage(message);
                if (exchange != null)
                {
                    exchange.listener.onMessages(Collections.singletonList(message));
                }
                else
                {
                    // If the exchange is missing, then the message has expired, and we do not notify
                    logger.debug("Could not find request for reply {}", message);
                }

                if (_disconnected && !_connected)
                    disconnect("Disconnect");
            }
            else
            {
                _listener.onMessages(Collections.singletonList(message));
            }
        }
    }

    private static class WebSocketExchange
    {
        private final Mutable message;
        private final TransportListener listener;
        private final ScheduledFuture<?> task;

        public WebSocketExchange(Mutable message, TransportListener listener, ScheduledFuture<?> task)
        {
            this.message = message;
            this.listener = listener;
            this.task = task;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + " " + message;
        }
    }
}
