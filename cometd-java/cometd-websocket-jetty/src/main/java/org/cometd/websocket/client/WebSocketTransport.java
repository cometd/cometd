/*
 * Copyright (c) 2008-2014 the original author or authors.
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
import java.nio.channels.UnresolvedAddressException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.TransportException;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class WebSocketTransport extends HttpClientTransport implements MessageClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public final static String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public final static String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";
    public final static String STICKY_RECONNECT_OPTION = "stickyReconnect";

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

    private final WebSocketClientFactory _webSocketClientFactory;
    private ScheduledExecutorService _scheduler;
    private boolean _shutdownScheduler;
    private String _protocol;
    private long _connectTimeout;
    private int _idleTimeout;
    private int _maxMessageSize;
    private boolean _stickyReconnect;
    private boolean _webSocketSupported;
    private boolean _webSocketConnected;
    private Delegate _delegate;
    private TransportListener _listener;

    public WebSocketTransport(Map<String, Object> options, WebSocketClientFactory webSocketClientFactory, ScheduledExecutorService scheduler)
    {
        this(null, options, webSocketClientFactory, scheduler);
    }

    public WebSocketTransport(String url, Map<String, Object> options, WebSocketClientFactory webSocketClientFactory, ScheduledExecutorService scheduler)
    {
        super(NAME, url, options);
        _webSocketClientFactory = webSocketClientFactory;
        _scheduler = scheduler;
        setOptionPrefix(PREFIX);
        _webSocketSupported = true;
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
        if (_scheduler == null)
        {
            _scheduler = Executors.newSingleThreadScheduledExecutor();
            _shutdownScheduler = true;
        }
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        setMaxNetworkDelay(15000L);
        _connectTimeout = 30000L;
        _idleTimeout = 60000;
        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClientFactory.getBufferSize());
        _stickyReconnect = getOption(STICKY_RECONNECT_OPTION, true);
    }

    private long getConnectTimeout()
    {
        return _connectTimeout = getOption(CONNECT_TIMEOUT_OPTION, _connectTimeout);
    }

    private int getIdleTimeout()
    {
        return _idleTimeout = getOption(IDLE_TIMEOUT_OPTION, _idleTimeout);
    }

    @Override
    public void abort()
    {
        Delegate delegate = getDelegate();
        if (delegate != null)
            delegate.abort();
        shutdownScheduler();
    }

    @Override
    public void terminate()
    {
        Delegate delegate = getDelegate();
        if (delegate != null)
            delegate.terminate();
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

    private Delegate getDelegate()
    {
        synchronized (this)
        {
            return _delegate;
        }
    }

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        Delegate delegate = getDelegate();
        if (delegate == null)
        {
            delegate = connect(listener, messages);
            if (delegate == null)
                return;
        }

        delegate.registerMessages(listener, messages);

        try
        {
            String json = generateJSON(messages);

            debug("Sending messages {}", json);
            // The onSending() callback must be invoked before the actual send
            // otherwise we may have a race condition where the response is so
            // fast that it arrives before the onSending() is called.
            listener.onSending(messages);
            delegate.send(json);
        }
        catch (Exception x)
        {
            delegate.fail(x, "Exception");
        }
    }

    private Delegate connect(TransportListener listener, Mutable[] messages)
    {
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

            Delegate delegate = connect(client, uri);
            synchronized (this)
            {
                if (_delegate != null)
                {
                    // We connected concurrently, keep only one.
                    delegate.close("Extra");
                    delegate = _delegate;
                }
                _delegate = delegate;
            }

            // Connection was successful
            _webSocketConnected = true;

            return delegate;
        }
        catch (ConnectException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (UnresolvedAddressException x)
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
        catch (InterruptedException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (ProtocolException x)
        {
            // Probably a WebSocket upgrade failure
            _webSocketSupported = false;
            // Try to parse the HTTP error, although it's ugly
            Map<String, Object> failure = new HashMap<String, Object>(2);
            failure.put("websocketCode", 1002);
            // Unfortunately the information on the HTTP status code is not available directly
            // Try to parse it although it's ugly
            Matcher matcher = Pattern.compile("(\\d+){3}").matcher(x.getMessage());
            if (matcher.find())
            {
                int code = Integer.parseInt(matcher.group());
                if (code > 100 && code < 600)
                    failure.put("httpCode", code);
            }
            listener.onException(new TransportException(x, failure), messages);
        }
        catch (Exception x)
        {
            _webSocketSupported = _stickyReconnect && _webSocketConnected;
            listener.onException(x, messages);
        }
        return null;
    }

    protected Delegate connect(WebSocketClient client, URI uri) throws IOException, InterruptedException, TimeoutException
    {
        Delegate result = new Delegate();
        client.open(uri, result, getConnectTimeout(), TimeUnit.MILLISECONDS);
        return result;
    }

    protected WebSocketClient newWebSocketClient()
    {
        WebSocketClient result = _webSocketClientFactory.newWebSocketClient();
        result.setMaxTextMessageSize(_maxMessageSize);
        result.setMaxIdleTime(getIdleTimeout());
        return result;
    }

    protected class Delegate implements WebSocket.OnTextMessage
    {
        private final Map<String, WebSocketExchange> _exchanges = new ConcurrentHashMap<String, WebSocketExchange>();
        private volatile Connection _connection;
        private volatile boolean _aborted;
        private volatile boolean _connected;
        private volatile boolean _disconnected;
        private volatile Map<String, Object> _advice;

        public void onOpen(Connection connection)
        {
            _connection = connection;
            debug("Opened websocket connection {}", connection);
        }

        public void onClose(int closeCode, String message)
        {
            boolean proceed = false;
            synchronized (WebSocketTransport.this)
            {
                if (this == _delegate)
                {
                    _delegate = null;
                    proceed = true;
                }
            }

            if (proceed)
            {
                debug("Closed websocket connection with code {} {}: {} ", closeCode, message, _connection);
                failMessages(new EOFException("Connection closed " + closeCode + " " + message));
            }
        }

        public void onMessage(String data)
        {
            try
            {
                List<Mutable> messages = parseMessages(data);

                boolean proceed;
                synchronized (WebSocketTransport.this)
                {
                    proceed = this == _delegate;
                }
                if (proceed)
                {
                    debug("Received messages {}", data);
                    onMessages(messages);
                }
                else
                {
                    debug("Discarding messages {}", messages);
                }
            }
            catch (ParseException x)
            {
                fail(x, "Exception");
            }
        }

        private void onMessages(List<Mutable> messages)
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
                        debug("Could not find request for reply {}", message);
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

        private boolean isReply(Message message)
        {
            return message.isMeta() || message.isPublishReply();
        }

        private void registerMessages(TransportListener listener, Mutable[] messages)
        {
            boolean aborted;
            synchronized (this)
            {
                aborted = _aborted;
                if (!aborted)
                {
                    for (Mutable message : messages)
                        registerMessage(message, listener);
                }
            }
            if (aborted)
                listener.onException(new IOException("Aborted"), messages);
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
                _connected = true;
            }

            // Schedule a task to expire if the maxNetworkDelay elapses.
            final long expiration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + maxNetworkDelay;
            ScheduledFuture<?> task = _scheduler.schedule(new Runnable()
            {
                public void run()
                {
                    long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                    long delay = now - expiration;
                    if (delay > 5000) // TODO: make the max delay a parameter ?
                        debug("Message {} expired {} ms too late", message, delay);
                    debug("Expiring message {}", message);
                    fail(new TimeoutException(), "Expired");
                }
            }, maxNetworkDelay, TimeUnit.MILLISECONDS);

            // Register the exchange
            // Message responses must have the same messageId as the requests

            WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
            debug("Registering {}", exchange);
            Object existing = _exchanges.put(message.getId(), exchange);
            // Paranoid check
            if (existing != null)
                throw new IllegalStateException();
        }

        private WebSocketExchange deregisterMessage(Message message)
        {
            WebSocketExchange exchange = _exchanges.remove(message.getId());
            if (Channel.META_CONNECT.equals(message.getChannel()))
                _connected = false;
            else if (Channel.META_DISCONNECT.equals(message.getChannel()))
                _disconnected = true;

            debug("Deregistering {} for message {}", exchange, message);

            if (exchange != null)
                exchange.task.cancel(false);

            return exchange;
        }

        private void send(String text) throws IOException
        {
            Connection connection = _connection;
            if (connection == null)
                throw new IOException("Could not send " + text);
            connection.sendMessage(text);
        }

        private void fail(Exception failure, String reason)
        {
            disconnect(reason);
            failMessages(failure);
        }

        private void failMessages(Throwable cause)
        {
            List<WebSocketExchange> exchanges;
            synchronized (this)
            {
                exchanges = new ArrayList<WebSocketExchange>(_exchanges.values());
            }
            for (WebSocketExchange exchange : exchanges)
            {
                Mutable message = exchange.message;
                if (deregisterMessage(message) == exchange)
                    exchange.listener.onException(cause, new Message[]{message});
            }
        }

        public void abort()
        {
            synchronized (this)
            {
                _aborted = true;
            }
            fail(new IOException("Aborted"), "Aborted");
        }

        private void disconnect(String reason)
        {
            boolean close;
            synchronized (WebSocketTransport.this)
            {
                close = this == _delegate;
                if (close)
                    _delegate = null;
            }
            if (close)
                close(reason);
        }

        private void close(String reason)
        {
            Connection connection = _connection;
            if (connection != null && connection.isOpen())
            {
                debug("Closing ({}) websocket connection {}", reason, connection);
                connection.close(1000, reason);
            }
        }

        private void terminate()
        {
            fail(new EOFException(), "Terminate");
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
