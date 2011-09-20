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

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class WebSocketTransport extends HttpClientTransport implements MessageClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public final static String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";
    public final static String UNIQUE_MESSAGE_ID_GUARANTEED_OPTION = "uniqueMessageIdGuaranteed";

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClientFactory webSocketClientFactory)
    {
        return create(options, webSocketClientFactory, null);
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClientFactory webSocketClientFactory, ScheduledExecutorService scheduler)
    {
        WebSocketTransport transport = new WebSocketTransport(options, webSocketClientFactory, scheduler);
        if (!webSocketClientFactory.isStarted())
        {
            try
            {
                webSocketClientFactory.start();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
        return transport;
    }

    private final WebSocket _websocket = new CometDWebSocket();
    private final Map<String, WebSocketExchange> _metaExchanges = new ConcurrentHashMap<String, WebSocketExchange>();
    private final Map<String, List<WebSocketExchange>> _exchanges = new HashMap<String, List<WebSocketExchange>>();
    private final WebSocketClientFactory _webSocketClientFactory;
    private volatile ScheduledExecutorService _scheduler;
    private volatile boolean _shutdownScheduler;
    private volatile String _protocol = "cometd";
    private volatile long _maxNetworkDelay = 15000L;
    private volatile long _connectTimeout = 30000L;
    private volatile int _maxMessageSize;
    private volatile boolean _uniqueMessageId = true;
    private boolean _aborted;
    private volatile boolean _webSocketSupported = true;
    private volatile WebSocket.Connection _connection;
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;

    public WebSocketTransport(Map<String, Object> options, WebSocketClientFactory webSocketClientFactory, ScheduledExecutorService scheduler)
    {
        super(NAME, options);
        _webSocketClientFactory = webSocketClientFactory;
        _scheduler = scheduler;
        setOptionPrefix(PREFIX);
    }

    public void setMessageTransportListener(TransportListener listener)
    {
        _listener = listener;
    }

    public boolean accept(String version)
    {
        return _webSocketSupported;
    }

    @Override
    public void init()
    {
        super.init();
        _aborted = false;
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, _maxNetworkDelay);
        _connectTimeout = getOption(CONNECT_TIMEOUT_OPTION, _connectTimeout);
        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClientFactory.getBufferSize());
        _uniqueMessageId = getOption(UNIQUE_MESSAGE_ID_GUARANTEED_OPTION, _uniqueMessageId);
        if (_scheduler == null)
        {
            _shutdownScheduler = true;
            _scheduler = Executors.newSingleThreadScheduledExecutor();
        }
    }

    private long getMaxNetworkDelay()
    {
        return _maxNetworkDelay;
    }

    private long getConnectTimeout()
    {
        return _connectTimeout;
    }

    @Override
    public void abort()
    {
        _aborted = true;
        disconnect();
        reset();
    }

    @Override
    public void reset()
    {
        super.reset();
        if (_shutdownScheduler)
        {
            _shutdownScheduler = false;
            _scheduler.shutdown();
            _scheduler = null;
        }
    }

    @Override
    public void terminate()
    {
        super.terminate();
        disconnect();
    }

    private void disconnect()
    {
        Connection connection = _connection;
        _connection = null;
        if (connection != null && connection.isOpen())
        {
            debug("Disconnecting websocket connection {}", connection);
            connection.disconnect();
        }
    }

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        if (_aborted)
            throw new IllegalStateException("Aborted");

        try
        {
            String content = generateJSON(messages);

            Connection connection = connect(listener, messages);
            if (connection == null)
                return;

            for (Message.Mutable message : messages)
                registerMessage(message, listener);

            debug("Sending messages {}", content);
            // The onSending() callback must be invoked before the actual send
            // otherwise we may have a race condition where the response is so
            // fast that it arrives before the onSending() is called.
            listener.onSending(messages);
            connection.sendMessage(content);
        }
        catch (Exception x)
        {
            complete(messages);
            listener.onException(x, messages);
        }
    }

    private Connection connect(TransportListener listener, Mutable[] messages)
    {
        Connection connection = _connection;
        if (connection != null)
            return connection;

        try
        {
            // Mangle the URL
            String url = getURL();
            url = url.replaceFirst("^http", "ws");
            URI uri = new URI(url);
            debug("Opening websocket connection to {}", uri);

            // Prepare the cookies
            Map<String, String> cookies = new HashMap<String, String>();
            for (Cookie cookie : getCookieProvider().getCookies())
                cookies.put(cookie.getName(), cookie.getValue());

            WebSocketClient client = newWebSocketClient();
            client.setProtocol(_protocol);
            client.getCookies().putAll(cookies);

            _connection = client.open(uri, _websocket, getConnectTimeout(), TimeUnit.MILLISECONDS);

            if (_aborted)
            {
                listener.onException(new IOException("Aborted"), messages);
            }

            return _connection;
        }
        catch (ConnectException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (SocketTimeoutException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (TimeoutException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (URISyntaxException x)
        {
            _webSocketSupported = false;
            listener.onProtocolError(x.getMessage(), messages);
        }
        catch (InterruptedException x)
        {
            _webSocketSupported = false;
            listener.onException(x, messages);
        }
        catch (ProtocolException x)
        {
            _webSocketSupported = false;
            listener.onProtocolError(x.getMessage(), messages);
        }
        catch (IOException x)
        {
            _webSocketSupported = false;
            listener.onException(x, messages);
        }
        return _connection;
    }

    protected WebSocketClient newWebSocketClient()
    {
        WebSocketClient result = _webSocketClientFactory.newWebSocketClient();
        result.setMaxTextMessageSize(_maxMessageSize);
        return result;
    }

    private void complete(Message.Mutable[] messages)
    {
        for (Message.Mutable message : messages)
            deregisterMessage(message);
    }

    private void registerMessage(final Message.Mutable message, final TransportListener listener)
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
        }

        // Schedule a task to expire if the maxNetworkDelay elapses
        final long expiration = System.currentTimeMillis() + maxNetworkDelay;
        ScheduledFuture<?> task = _scheduler.schedule(new Runnable()
        {
            public void run()
            {
                long now = System.currentTimeMillis();
                long jitter = now - expiration;
                if (jitter > 5000) // TODO: make the max jitter a parameter ?
                    debug("Expired too late {} for {}", jitter, message);

                // Notify only if we won the race to deregister the message
                WebSocketExchange exchange = deregisterMessage(message);
                if (exchange != null)
                    listener.onExpire(new Message[]{message});
            }
        }, maxNetworkDelay, TimeUnit.MILLISECONDS);

        // Register the exchange
        // Message responses must have the same messageId as the requests
        // Meta messages have unique messageIds, but this may not be true
        // for publish messages where the application can specify its own
        // messageId, so we need to take that in account

        WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
        debug("Registering {}", exchange);
        if (_uniqueMessageId || message.isMeta())
        {
            Object existing = _metaExchanges.put(message.getId(), exchange);
            // Paranoid check
            if (existing != null)
                throw new IllegalStateException();
        }
        else
        {
            synchronized (this)
            {
                List<WebSocketExchange> exchanges = _exchanges.get(message.getId());
                if (exchanges == null)
                {
                    exchanges = new LinkedList<WebSocketExchange>();
                    Object existing = _exchanges.put(message.getId(), exchanges);
                    // Paranoid check
                    if (existing != null)
                        throw new IllegalStateException();
                }
                exchanges.add(exchange);
            }
        }
    }

    private WebSocketExchange deregisterMessage(Message message)
    {
        WebSocketExchange exchange = null;
        if (_uniqueMessageId || message.isMeta())
        {
            exchange = _metaExchanges.remove(message.getId());
        }
        else
        {
            // Check if it is a publish reply
            if (isPublishReply(message))
            {
                synchronized (this)
                {
                    List<WebSocketExchange> exchanges = _exchanges.get(message.getId());
                    if (exchanges != null)
                    {
                        for (int i = 0; i < exchanges.size(); ++i)
                        {
                            WebSocketExchange x = exchanges.get(i);
                            if (message.getChannel().equals(x.message.getChannel()))
                            {
                                exchanges.remove(x);
                                if (exchanges.isEmpty())
                                    _exchanges.remove(message.getId());
                                exchange = x;
                                break;
                            }
                        }
                    }
                }
            }
        }

        debug("Deregistering {} for response {}", exchange, message);

        if (exchange != null)
            exchange.task.cancel(false);

        return exchange;
    }

    private boolean isReply(Message message)
    {
        return message.isMeta() || isPublishReply(message);
    }

    private boolean isPublishReply(Message message)
    {
        return !message.containsKey(Message.DATA_FIELD) && !message.isMeta();
    }

    private void failMessages(Throwable cause)
    {
        List<WebSocketExchange> exchanges = new ArrayList<WebSocketExchange>(_metaExchanges.values());
        for (WebSocketExchange exchange : exchanges)
        {
            deregisterMessage(exchange.message);
            exchange.listener.onException(cause, new Message[]{exchange.message});
        }

        if (!_uniqueMessageId)
        {
            exchanges = new ArrayList<WebSocketExchange>();
            synchronized (this)
            {
                for (List<WebSocketExchange> exchangeList : _exchanges.values())
                {
                    exchanges.addAll(exchangeList);
                    for (WebSocketExchange exchange : exchangeList)
                        deregisterMessage(exchange.message);
                }
            }
            for (WebSocketExchange exchange : exchanges)
                exchange.listener.onException(cause, new Message[]{exchange.message});
        }
    }

    protected void onMessages(List<Mutable> messages)
    {
        for (Mutable message : messages)
        {
            if (Channel.META_CONNECT.equals(message.getChannel()) && message.isSuccessful())
            {
                // Remember the advice so that we can properly calculate the max network delay
                Map<String, Object> advice = message.getAdvice();
                if (advice != null && advice.get(Message.TIMEOUT_FIELD) != null)
                    _advice = advice;
            }

            if (isReply(message))
            {
                WebSocketExchange exchange = deregisterMessage(message);
                if (exchange != null)
                {
                    exchange.listener.onMessages(Collections.singletonList(message));
                }
                else
                {
                    // If the exchange is missing, then the message has expired, and we do not notify
                    debug("Could not find request for reply {}", message);
                }
            }
            else
            {
                _listener.onMessages(Collections.singletonList(message));
            }
        }
    }

    protected class CometDWebSocket implements WebSocket.OnTextMessage
    {
        public void onOpen(Connection connection)
        {
            debug("Opened websocket connection {}", connection);
        }

        public void onClose(int closeCode, String message)
        {
            Connection connection = _connection;
            _connection = null;
            debug("Closed websocket connection with code {} {}: {} ", closeCode, message, connection);
            failMessages(new EOFException("Connection closed " + closeCode+" "+message));
        }

        public void onMessage(String data)
        {
            try
            {
                List<Mutable> messages = parseMessages(data);
                debug("Received messages {}", data);
                onMessages(messages);
            }
            catch (ParseException x)
            {
                _listener.onException(x, new Message[0]);
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
