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

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocketClient;

public class WebSocketTransport extends HttpClientTransport implements MessageClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String CONNECT_TIMEOUT = "connectTimeout";

    public static WebSocketTransport create(Map<String, Object> options)
    {
        return create(options, new WebSocketClient());
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient websocketClient)
    {
        return create(options, websocketClient, null);
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient websocketClient, ScheduledExecutorService scheduler)
    {
        WebSocketTransport transport = new WebSocketTransport(options, websocketClient, scheduler);
        if (!websocketClient.isStarted())
        {
            try
            {
                websocketClient.start();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
        return transport;
    }

    private final Logger logger = Log.getLogger(getClass().getName());
    private final WebSocketClient _webSocketClient;
    private final ScheduledExecutorService _scheduler;
    private final boolean _shutdownScheduler;
    private final WebSocket _websocket = new CometDWebSocket();
    private final Map<String, WebSocketExchange> _metaExchanges = new HashMap<String, WebSocketExchange>();
    private final Map<String, List<WebSocketExchange>> _exchanges = new HashMap<String, List<WebSocketExchange>>();
    private boolean _aborted;
    private volatile boolean _webSocketSupported = true;
    private volatile WebSocket.Connection _connection;
    private volatile String _protocol = "cometd";
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;

    public WebSocketTransport(Map<String, Object> options, WebSocketClient client, ScheduledExecutorService scheduler)
    {
        super(NAME, options);
        _webSocketClient = client;
        _scheduler = scheduler == null ? Executors.newSingleThreadScheduledExecutor() : scheduler;
        _shutdownScheduler = scheduler == null;
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
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
    }

    private int getMaxNetworkDelay()
    {
        return getOption(MAX_NETWORK_DELAY_OPTION, 5000);
    }

    @Override
    public void abort()
    {
        final Connection connection = _connection;
        if (connection != null)
            connection.disconnect();
    }

    @Override
    public void reset()
    {
        abort();
        if (_shutdownScheduler)
            _scheduler.shutdown();
    }

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        if (_aborted)
            throw new IllegalStateException("Aborted");

        try
        {
            // TODO: avoid JSON dependency
            String content = JSON.toString(messages);

            for (Message.Mutable message : messages)
                registerMessage(message, listener);

            Connection connection = connect(listener, messages);
            if (connection == null)
                return;

            logger.debug("Sending messages {}", content);
            connection.sendMessage(content);
            listener.onSending(messages);
        }
        catch (Exception x)
        {
            listener.onException(x, messages);
        }
    }

    private Connection connect(TransportListener listener, Mutable[] messages)
    {
        Connection connection = _connection;
        if (connection != null)
            return connection;

        // TODO: the key point here is to differentiate between critical failure
        // TODO: e.g. server does not support websocket and retry-able failures
        // TODO: e.g. ConnectException

        // Mangle the URL
        String url = getURL();
        url = url.replaceFirst("^http", "ws");

        // Prepare the cookies
        Map<String, String> cookies = new HashMap<String, String>();
        for (Cookie cookie : getCookieProvider().getCookies())
            cookies.put(cookie.getName(), cookie.getValue());

        try
        {
/*
            WebSocketClient client=new WebSocketClient(_webSocketClient);
            client.setMaxIdleTime(maxIdleTime);
            client.setProtocol(_protocol);
            client.getCookies().putAll(cookies);
            _handshake=client.open(uri,_websocket);
*/

            URI uri = new URI(url);
            logger.debug("Opening websocket connection to {}", uri);
//            _webSocketClient.open(uri, _websocket, _protocol, 0, cookies, null);
            return _connection;
        }
        catch (URISyntaxException x)
        {
            listener.onProtocolError(x.getMessage(), messages);
        }
/*
        catch (ConnectException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (SocketTimeoutException x)
        {
            listener.onConnectException(x, messages);
        }
        catch (IOException x)
        {
            listener.onException(x, messages);
        }
*/
        return null;
    }

    private void registerMessage(Message.Mutable message, TransportListener listener)
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
        ScheduledFuture<?> task = _scheduler.schedule(new Runnable()
        {
            public void run()
            {
                // TODO: expire the message
            }
        }, maxNetworkDelay, TimeUnit.MILLISECONDS);

        // Register the exchange
        // Message responses must have the same messageId as the requests
        // Meta messages have unique messageIds, but this may not be true
        // for publish messages where the application can specify its own
        // messageId, so we need to take that in account

        WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
        if (message.isMeta())
        {
            synchronized (this)
            {
                Object existing = _metaExchanges.put(message.getId(), exchange);
                // Paranoid check
                if (existing != null)
                    throw new IllegalStateException();
            }
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
        if (message.isMeta())
        {
            synchronized (this)
            {
                exchange = _metaExchanges.remove(message.getId());
            }
        }
        else
        {
            // Check if it is a publish reply
            if (message.getData() == null)
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

        if (exchange != null)
            exchange.task.cancel(true);

        return exchange;
    }

    private void failExchangesForProtocol(String error)
    {
        synchronized (this)
        {
            for (WebSocketExchange exchange : _metaExchanges.values())
            {
                exchange.task.cancel(false);
                exchange.listener.onProtocolError(error, new Message[]{exchange.message});
            }
            _metaExchanges.clear();
            for (List<WebSocketExchange> exchanges : _exchanges.values())
            {
                for (WebSocketExchange exchange : exchanges)
                {
                    exchange.task.cancel(false);
                    exchange.listener.onProtocolError(error, new Message[]{exchange.message});
                }
            }
            _exchanges.clear();
        }
    }

    protected class CometDWebSocket implements WebSocket.OnTextMessage
    {
        public void onOpen(Connection connection)
        {
            // TODO: fix the websocket client, it should not pass a closed connection
            if (!connection.isOpen())
                return;

            _connection = connection;
            logger.debug("Opened websocket connection {}", connection);
        }

        public void onClose(int closeCode, String message)
        {
            final Connection connection = _connection;
            logger.debug("Closed websocket connection {}", connection);
            synchronized (this)
            {
                for (WebSocketExchange exchange : _metaExchanges.values())
                    exchange.task.cancel(false);
                for (List<WebSocketExchange> exchanges : _exchanges.values())
                    for (WebSocketExchange exchange : exchanges)
                        exchange.task.cancel(false);
            }
            // TODO: must count down opened ?
//            _opened.countDown();
        }

        public void onError(String message, Throwable ex)
        {
            _webSocketSupported = false;
            _aborted = true;
            failExchangesForProtocol(message);
        }

        public void onMessage(String data)
        {
            try
            {
                List<Mutable> messages = parseMessages(data);
                logger.debug("Received messages {}", messages);
                for (Mutable message : messages)
                {
                    if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel()))
                    {
                        // Remember the advice so that we can properly calculate the max network delay
                        Map<String, Object> advice = message.getAdvice();
                        if (advice != null && advice.get("timeout") != null)
                            _advice = advice;
                    }
                    WebSocketExchange exchange = deregisterMessage(message);
                    if (exchange != null)
                        exchange.listener.onMessages(Collections.singletonList(message));
                    else
                        _listener.onMessages(Collections.singletonList(message));
                }
            }
            catch (ParseException x)
            {
                _listener.onException(x, new Message[0]);
            }
        }
    }

    private class WebSocketExchange
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
    }
}
