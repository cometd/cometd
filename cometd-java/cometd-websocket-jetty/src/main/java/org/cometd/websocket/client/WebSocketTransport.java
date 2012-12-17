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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketTransport extends HttpClientTransport implements MessageClientTransport
{
    private final Logger logger = LoggerFactory.getLogger(getClass().getName() + "." + System.identityHashCode(this));

    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public final static String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public final static String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";

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

    private final Map<String, WebSocketExchange> _metaExchanges = new ConcurrentHashMap<>();
    private final WebSocketClientFactory _webSocketClientFactory;
    private volatile ScheduledExecutorService _scheduler;
    private volatile boolean _shutdownScheduler;
    private volatile String _protocol = "cometd";
    private volatile long _maxNetworkDelay = 15000L;
    private volatile long _connectTimeout = 30000L;
    private volatile int _idleTimeout = 60000;
    private volatile int _maxMessageSize;
    private volatile boolean _connected;
    private volatile boolean _disconnected;
    private volatile boolean _aborted;
    private volatile boolean _webSocketSupported = true;
    private volatile WebSocketLink _wslink;
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
        _idleTimeout = getOption(IDLE_TIMEOUT_OPTION, _idleTimeout);
        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClientFactory.getPolicy().getBufferSize());

        _webSocketClientFactory.getPolicy().setIdleTimeout(_idleTimeout);
        _webSocketClientFactory.getPolicy().setMaxTextMessageSize(_maxMessageSize);
        if (_scheduler == null)
        {
            _shutdownScheduler = true;
            _scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        logger.debug("transport {}", this);
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
        logger.debug("client aborting");
        _aborted = true;
        disconnect("Aborted");
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
        disconnect("Terminated");
    }

    protected void disconnect(String reason)
    {
        WebSocketLink wslink = _wslink;
        _wslink = null;
        if (wslink != null)
        {
            WebSocketConnection connection = wslink.getConnection();
            if (connection != null && connection.isOpen())
            {
                debug("Closing websocket connection {}", connection);
                try
                {
                    connection.close(1000, reason);
                }
                catch (IOException x)
                {
                    logger.debug("", x);
                }
            }
        }
    }

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        if (_aborted)
            throw new IllegalStateException("Aborted");

        try
        {
            WebSocketLink wslink = connect(listener, messages);
            if (wslink == null)
                return;

            for (Message.Mutable message : messages)
            {
                registerMessage(message, listener);
            }

            String content = generateJSON(messages);

            debug("Sending messages {}", content);
            // The onSending() callback must be invoked before the actual send
            // otherwise we may have a race condition where the response is so
            // fast that it arrives before the onSending() is called.
            listener.onSending(messages);

            Future<Void> result = wslink.getConnection().write(content);

            result.get();
        }
        catch (Exception x)
        {
            x.printStackTrace();
            complete(messages);
            disconnect("Exception");
            listener.onFailure(x, messages);
        }
    }

    private WebSocketLink connect(TransportListener listener, Mutable[] messages)
    {
        WebSocketLink wslink = _wslink;
        if (wslink != null)
            return wslink;

        try
        {
            // Mangle the URL
            String url = getURL();
            url = url.replaceFirst("^http", "ws");
            URI uri = new URI(url);
            debug("Opening websocket connection to {}", uri);

            WebSocketLink link = newWebSocketLink();
            boolean connectStatus = link.connect(uri);
            if (!connectStatus)
            {
                listener.onFailure(new TimeoutException("Connect Timeout"), messages);
                return null;
            }

            if (_aborted)
            {
                listener.onFailure(new Exception("Aborted"), messages);
                return null;
            }

            _wslink = link;
            return link;
        }
        catch (ConnectException | SocketTimeoutException | TimeoutException x)
        {
            listener.onFailure(x, messages);
        }
        catch (URISyntaxException x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }
        catch (InterruptedException x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }
        catch (ProtocolException x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }
        catch (IOException x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }
        catch (IllegalStateException x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }
        catch (UpgradeException x)
        {
            _webSocketSupported = false;
            listener.onFailure(new ProtocolException().initCause(x.getCause()), messages);
        }
        catch (Exception x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }

        return null;
    }

    protected WebSocketLink newWebSocketLink()
    {
        CometDWebSocket ws = new CometDWebSocket();
        WebSocketClient result = _webSocketClientFactory.newWebSocketClient(ws);
        result.getPolicy().setMaxTextMessageSize(_maxMessageSize);
        result.getPolicy().setIdleTimeout(_idleTimeout);
        result.getUpgradeRequest().setCookieStore(getCookieStore());
        // TODO: how to set the websocket protocol ?
        return new WebSocketLink(result,ws);
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
            _connected = true;
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
                {
                    listener.onFailure(new TimeoutException("Exchange expired"), new Message[]{message});
                }
            }
        }, maxNetworkDelay, TimeUnit.MILLISECONDS);

        // Register the exchange
        // Message responses must have the same messageId as the requests

        WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
        debug("Registering {}", exchange);
        Object existing = _metaExchanges.put(message.getId(), exchange);
        // Paranoid check
        if (existing != null)
            throw new IllegalStateException();
    }

    private WebSocketExchange deregisterMessage(Message message)
    {
        WebSocketExchange exchange = _metaExchanges.remove(message.getId());
        if (Channel.META_CONNECT.equals(message.getChannel()))
            _connected = false;
        else if (Channel.META_DISCONNECT.equals(message.getChannel()))
            _disconnected = true;

        debug("Deregistering {} for message {}", exchange, message);

        if (exchange != null)
            exchange.task.cancel(false);

        return exchange;
    }

    private boolean isReply(Message message)
    {
        return message.isMeta() || message.isPublishReply();
    }

    private void failMessages(Throwable cause)
    {
        List<WebSocketExchange> exchanges = new ArrayList<>(_metaExchanges.values());
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

    @WebSocket
    protected class CometDWebSocket
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private WebSocketConnection _connection;

        @OnWebSocketConnect
        public void onOpen(WebSocketConnection connection)
        {
            logger.debug("WebSocketClient: Notified of Open");

            _connection = connection;
            latch.countDown();
            debug("Opened websocket connection {}", connection);
        }

        @OnWebSocketClose
        public void onClose(int closeCode, String message)
        {
            logger.debug("WebSocketClient: Notified of Close");

            _connection = null;
            debug("Closed websocket connection with code {} {}: {} ", closeCode, message, _connection);
            failMessages(new EOFException("Connection closed " + closeCode + " " + message));
        }

        @OnWebSocketMessage
        public void onMessage(String data)
        {
            logger.debug("WebSocketClient: Notified of Text");

            try
            {
                List<Mutable> messages = parseMessages(data);
                debug("Received messages {}", data);
                onMessages(messages);
            }
            catch (ParseException x)
            {
                failMessages(x);
                disconnect("Exception");
            }
        }

        public WebSocketConnection getConnection()
        {
            return _connection;
        }

        public boolean await(long timeout)
        {
            try
            {
               return latch.await(timeout,TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                return false;
            }
        }
    }

    private class WebSocketLink
    {
        private WebSocketClient _client;
        private CometDWebSocket _websocket;

        private WebSocketLink(WebSocketClient client, CometDWebSocket websocket)
        {
            _client = client;
            _websocket = websocket;
        }

        public WebSocketConnection getConnection()
        {
            return _websocket.getConnection();
        }

        protected boolean connect(URI uri) throws Exception
        {
            try
            {
                _client.connect(uri).get(_connectTimeout, TimeUnit.MILLISECONDS);
                return _websocket.await(_connectTimeout);
            }
            catch (ExecutionException x)
            {
                Throwable cause = x.getCause();
                if (cause instanceof Exception)
                    throw (Exception)cause;
                else
                    throw (Error)cause;
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
