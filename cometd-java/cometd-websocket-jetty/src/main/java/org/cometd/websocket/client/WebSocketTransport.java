package org.cometd.websocket.client;

import java.net.ConnectException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocketClient;

public class WebSocketTransport extends HttpClientTransport
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";

    public static WebSocketTransport create(Map<String, Object> options)
    {
        return create(options, new WebSocketClient(), Executors.newSingleThreadScheduledExecutor());
    }

    public static WebSocketTransport create(Map<String, Object> options, WebSocketClient websocketClient)
    {
        return create(options, websocketClient, Executors.newSingleThreadScheduledExecutor());
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
    private final WebSocket _websocket = new CometDWebSocket();
    private final CountDownLatch _opened = new CountDownLatch(1);
    private final ConcurrentMap<String, WebSocketExchange> _metaExchanges = new ConcurrentHashMap<String, WebSocketExchange>();
    private final ConcurrentMap<String, Queue<WebSocketExchange>> _exchanges = new ConcurrentHashMap<String, Queue<WebSocketExchange>>();
    private volatile WebSocket.Connection _connection;
    private String _protocol = "cometd";
    private volatile TransportListener _listener;
    private volatile Map<String, Object> _advice;

    public WebSocketTransport(Map<String, Object> options, WebSocketClient client, ScheduledExecutorService scheduler)
    {
        super(NAME, options);
        _webSocketClient = client;
        _scheduler = scheduler;
        setOptionPrefix(PREFIX);
    }

    public boolean accept(String version)
    {
        return true;
    }

    @Override
    public void init()
    {
        super.init();

        _protocol = getOption(PROTOCOL_OPTION, _protocol);

        int maxIdleTime = getOption(TIMEOUT_OPTION, 30000) +
                getOption(INTERVAL_OPTION, 10000) +
                getMaxNetworkDelay() * 2;

        Map<String, String> cookies = new HashMap<String, String>();
        for (Cookie cookie : getCookieProvider().getCookies())
            cookies.put(cookie.getName(), cookie.getValue());

        try
        {
            URI uri = new URI(getURL());
            logger.debug("Opening websocket connection to {}", uri);
            _webSocketClient.open(uri, _websocket, _protocol, maxIdleTime, cookies, null);
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    private int getMaxNetworkDelay()
    {
        return getOption(MAX_NETWORK_DELAY_OPTION, 5000);
    }

    @Override
    public void abort()
    {
        // TODO Auto-generated method stub

        System.err.println("abort ");
    }

    @Override
    public void reset()
    {
        // TODO Auto-generated method stub

        System.err.println("reset ");
        final Connection connection;
        synchronized (WebSocketTransport.this)
        {
            connection = _connection;
        }
        if (connection != null)
            connection.disconnect();
    }

    @Override
    public void send(TransportListener listener, Message.Mutable... messages)
    {
        try
        {
            _opened.await(_webSocketClient.getConnectTimeout(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException x)
        {
            logger.ignore(x);
        }

        // Grab the volatile field once
        final Connection connection = _connection;

        if (connection == null)
        {
            listener.onConnectException(new ConnectException(), messages);
            return;
        }

        // Any message we send will get a response
        // Connects may get a response later, and publish gets a response as well
        // The problem is to match the messageIds of the publishes

        try
        {
            String content = JSON.toString(messages);

            for (Message.Mutable message : messages)
                registerMessage(message, listener);

            logger.debug("Sending messages {}", content);
            connection.sendMessage(content);
            listener.onSending(messages);
        }
        catch (Exception x)
        {
            listener.onException(x, messages);
        }
    }

    private void registerMessage(Mutable message, TransportListener listener)
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
            Object existing = _metaExchanges.put(message.getId(), exchange);
            // Paranoid check
            if (existing != null)
                throw new IllegalStateException();
        }
        else
        {
            Queue<WebSocketExchange> exchanges = _exchanges.get(message.getId());
            if (exchanges == null)
            {
                exchanges = new ConcurrentLinkedQueue<WebSocketExchange>();
                Queue<WebSocketExchange> existing = _exchanges.putIfAbsent(message.getId(), exchanges);
                if (existing != null)
                    exchanges = existing;
            }
            exchanges.offer(exchange);
        }

    }

    protected class CometDWebSocket implements WebSocket.OnTextMessage
    {
        public void onOpen(Connection connection)
        {
            _connection = connection;
            logger.debug("Opened websocket connection {}", connection);
            _opened.countDown();
        }

        public void onClose(int closeCode, String message)
        {
            // TODO

            System.err.println("onClose " + closeCode + " " + message);
            synchronized (WebSocketTransport.this)
            {
                WebSocketTransport.this._connection = null;
            }
            _connection = null;

            // TODO Surely more to do here?
        }

        public void onError(String message, Throwable ex)
        {
            // TODO: lookup all inflight messages and fail them
        }

        public void onMessage(String data)
        {
            List<Message.Mutable> messages = parseMessages(data);
            logger.debug("Received messages {}", messages);
            for (Message.Mutable message : messages)
            {
                deregisterMessage(message);
                if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel()))
                {
                    // Remember the advice so that we can properly calculate the max network delay
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null && advice.get("timeout") != null)
                        _advice = advice;
                }
            }
            _listener.onMessages(messages);
        }
    }

    private class WebSocketExchange
    {

    }
}
