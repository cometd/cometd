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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.ServerProtocolException;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
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

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient webSocketClient)
    {
        return create(options, webSocketClient, null);
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient webSocketClient, ScheduledExecutorService scheduler)
    {
        WebSocketTransport transport = new WebSocketTransport(options, webSocketClient, scheduler);
        if (!webSocketClient.isStarted())
        {
            try
            {
                webSocketClient.start();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
        return transport;
    }

    private final CometDWebSocket _websocket = new CometDWebSocket();
    private final Map<String, WebSocketExchange> _metaExchanges = new ConcurrentHashMap<>();
    private final WebSocketClient _webSocketClient;
    private volatile ScheduledExecutorService _scheduler;
    private volatile boolean _shutdownScheduler;
    private volatile String _protocol = "cometd";
    private volatile long _maxNetworkDelay = 15000L;
    private volatile long _connectTimeout = 30000L;
    private volatile int _idleTimeout = 60000;
    private volatile long _maxMessageSize;
    private volatile boolean _connected;
    private volatile boolean _disconnected;
    private volatile boolean _aborted;
    private volatile boolean _webSocketSupported = true;
    private volatile Session _session;
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;

    public WebSocketTransport(Map<String, Object> options, WebSocketClient webSocketClient, ScheduledExecutorService scheduler)
    {
        super(NAME, options);
        _webSocketClient = webSocketClient;
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
        _maxNetworkDelay = getOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, _maxNetworkDelay);
        _connectTimeout = getOption(CONNECT_TIMEOUT_OPTION, _connectTimeout);
        _idleTimeout = getOption(IDLE_TIMEOUT_OPTION, _idleTimeout);
        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClient.getPolicy().getMaxMessageSize());

        _webSocketClient.setConnectTimeout(_connectTimeout);
        _webSocketClient.getPolicy().setIdleTimeout(_idleTimeout);
        _webSocketClient.getPolicy().setMaxMessageSize(_maxMessageSize);
        _webSocketClient.setCookieStore(getCookieStore());

        if (_scheduler == null)
        {
            _shutdownScheduler = true;
            _scheduler = Executors.newSingleThreadScheduledExecutor();
            // TODO: remove on cancel policy ? or reuse wsClient's ?
        }
    }

    @Override
    public void abort()
    {
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
        Session session = _session;
        _session = null;
        if (session != null && session.isOpen())
        {
            debug("Closing websocket connection {}", session);
            try
            {
                session.close(1000, reason);
            }
            catch (IOException x)
            {
                logger.trace("Could not close " + session, x);
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
            Session session = connect(listener, messages);
            if (session == null)
                return;

            for (Message.Mutable message : messages)
                registerMessage(message, listener);

            String content = generateJSON(messages);

            // The onSending() callback must be invoked before the actual send
            // otherwise we may have a race condition where the response is so
            // fast that it arrives before the onSending() is called.
            debug("Sending messages {}", content);
            listener.onSending(messages);

            session.getRemote().sendStringByFuture(content);
        }
        catch (Exception x)
        {
            complete(messages);
            disconnect("Exception");
            listener.onFailure(x, messages);
        }
    }

    private Session connect(TransportListener listener, Mutable[] messages)
    {
        Session session = _session;
        if (session != null)
            return session;

        try
        {
            // Mangle the URL
            String url = getURL();
            url = url.replaceFirst("^http", "ws");
            URI uri = URI.create(url);
            debug("Opening websocket connection to {}", uri);

            session = connect(uri);

            if (_aborted)
                listener.onFailure(new Exception("Aborted"), messages);

            return _session = session;
        }
        catch (ConnectException | SocketTimeoutException x)
        {
            // Cannot connect, assume the server supports WebSocket until proved otherwise
            listener.onFailure(x, messages);
        }
        catch (Exception x)
        {
            _webSocketSupported = false;
            listener.onFailure(x, messages);
        }

        return null;
    }

    protected Session connect(URI uri) throws IOException, InterruptedException
    {
        try
        {
            _webSocketClient.connect(_websocket, uri).get();
            // If the future succeeds, then we will have a non-null connection
            return _session;
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof UpgradeException) {
                int statusCode = ((UpgradeException)cause).getResponseStatusCode();
                throw new ServerProtocolException(x.getMessage(), statusCode, true);
            }
            if (cause instanceof IOException)
                throw (IOException)cause;
            throw new IOException(cause);
        }
    }

    private void complete(Message.Mutable[] messages)
    {
        for (Message.Mutable message : messages)
            deregisterMessage(message);
    }

    private void registerMessage(final Message.Mutable message, final TransportListener listener)
    {
        // Calculate max network delay
        long maxNetworkDelay = _maxNetworkDelay;
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
                    listener.onFailure(new TimeoutException("Exchange expired"), new Message[]{message});
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

    protected class CometDWebSocket implements WebSocketListener
    {
        @Override
        public void onWebSocketConnect(Session session)
        {
            _session = session;
            debug("Opened websocket session {}", session);
        }

        @Override
        public void onWebSocketClose(int closeCode, String reason)
        {
            debug("Closed websocket session with code {} {}: {} ", closeCode, reason, _session);
            _session = null;
            failMessages(new EOFException("Session closed " + closeCode + " " + reason));
        }

        @Override
        public void onWebSocketText(String data)
        {
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

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        @Override
        public void onWebSocketError(Throwable failure)
        {
            failMessages(failure);
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
