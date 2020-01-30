/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.client.websocket.common;

import java.io.EOFException;
import java.io.IOException;
import java.text.ParseException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketTransport extends HttpClientTransport implements MessageClientTransport {
    public static final String PREFIX = "ws";
    public static final String NAME = "websocket";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public static final String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public static final String STICKY_RECONNECT_OPTION = "stickyReconnect";
    public static final int MAX_CLOSE_REASON_LENGTH = 30;
    public static final int NORMAL_CLOSE_CODE = 1000;
    protected static final String COOKIE_HEADER = "Cookie";
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractWebSocketTransport.class);

    private ScheduledExecutorService _scheduler;
    private String _protocol;
    private long _connectTimeout;
    private long _idleTimeout;
    private boolean _stickyReconnect;
    private Delegate _delegate;
    private TransportListener _listener;

    protected AbstractWebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler) {
        super(NAME, url, options);
        _scheduler = scheduler;
        setOptionPrefix(PREFIX);
    }

    @Override
    public void setMessageTransportListener(TransportListener listener) {
        _listener = listener;
    }

    @Override
    public void setURL(String url) {
        // Mangle the URL
        super.setURL(url.replaceFirst("^http", "ws"));
    }

    @Override
    public void init() {
        super.init();

        if (_scheduler == null) {
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
            _scheduler = new WebSocketTransportScheduler(threads);
        }

        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        setMaxNetworkDelay(15000L);
        _connectTimeout = 30000L;
        _idleTimeout = 60000L;
        _stickyReconnect = getOption(STICKY_RECONNECT_OPTION, true);
    }

    public String getProtocol() {
        return _protocol;
    }

    public long getIdleTimeout() {
        return _idleTimeout = getOption(IDLE_TIMEOUT_OPTION, _idleTimeout);
    }

    public long getConnectTimeout() {
        return _connectTimeout = getOption(CONNECT_TIMEOUT_OPTION, _connectTimeout);
    }

    public boolean isStickyReconnect() {
        return _stickyReconnect;
    }

    @Override
    public void abort(Throwable failure) {
        Delegate delegate = getDelegate();
        if (delegate != null) {
            delegate.abort(failure);
        }
        shutdownScheduler();
    }

    @Override
    public void terminate() {
        Delegate delegate = getDelegate();
        if (delegate != null) {
            delegate.terminate();
        }
        shutdownScheduler();
        super.terminate();
    }

    private void shutdownScheduler() {
        if (_scheduler instanceof WebSocketTransportScheduler) {
            _scheduler.shutdown();
            _scheduler = null;
        }
    }

    protected Delegate getDelegate() {
        synchronized (this) {
            return _delegate;
        }
    }

    @Override
    public void send(TransportListener listener, List<Mutable> messages) {
        Delegate delegate = getDelegate();
        if (delegate == null) {
            // Mangle the URL
            String url = getURL();
            url = url.replaceFirst("^http", "ws");

            delegate = connect(url, listener, messages);

            if (delegate == null) {
                return;
            }

            synchronized (this) {
                if (_delegate != null) {
                    // We connected concurrently, keep only one.
                    delegate.shutdown("Extra");
                    delegate = _delegate;
                }
                _delegate = delegate;
            }
        }

        delegate.registerMessages(listener, messages);

        try {
            String content = generateJSON(messages);

            // The onSending() callback must be invoked before the actual send
            // otherwise we may have a race condition where the response is so
            // fast that it arrives before the onSending() is called.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Sending messages {}", content);
            }
            listener.onSending(messages);

            delegate.send(content);
        } catch (Throwable x) {
            delegate.fail(x, "Exception");
        }
    }

    protected abstract Delegate connect(String uri, TransportListener listener, List<Mutable> messages);

    protected abstract class Delegate {
        private final Map<String, WebSocketExchange> _exchanges = new ConcurrentHashMap<>();
        private boolean _connected;
        private boolean _disconnected;
        private Map<String, Object> _advice;

        protected void onClose(int code, String reason) {
            if (detach()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Closed websocket connection {}/{}", code, reason);
                }
                close();
                failMessages(new EOFException("Connection closed " + code + " " + reason));
            }
        }

        protected void onData(String data) {
            try {
                List<Mutable> messages = parseMessages(data);
                if (isAttached()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Received messages {}", data);
                    }
                    onMessages(messages);
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Discarded messages {}", data);
                    }
                }
            } catch (ParseException x) {
                fail(x, "Exception");
            }
        }

        protected void onMessages(List<Mutable> messages) {
            for (Mutable message : messages) {
                if (isReply(message)) {
                    // Remembering the advice must be done before we notify listeners
                    // otherwise we risk that listeners send a connect message that does
                    // not take into account the timeout to calculate the maxNetworkDelay
                    if (Channel.META_CONNECT.equals(message.getChannel()) && message.isSuccessful()) {
                        Map<String, Object> advice = message.getAdvice();
                        if (advice != null) {
                            // Remember the advice so that we can properly calculate the max network delay
                            if (advice.get(Message.TIMEOUT_FIELD) != null) {
                                _advice = advice;
                            }
                        }
                    }

                    WebSocketExchange exchange = deregisterMessage(message);
                    if (exchange != null) {
                        exchange.listener.onMessages(new ArrayList<>(Collections.singletonList(message)));
                    } else {
                        // If the exchange is missing, then the message has expired, and we do not notify
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Could not find request for reply {}", message);
                        }
                    }

                    if (_disconnected && !_connected) {
                        disconnect("Disconnect");
                    }
                } else {
                    _listener.onMessages(new ArrayList<>(Collections.singletonList(message)));
                }
            }
        }

        private boolean isReply(Message message) {
            if (message.isPublishReply()) {
                return true;
            }

            if (message.isMeta()) {
                // Check if it's a server-side disconnect.
                if (Channel.META_DISCONNECT.equals(message.getChannel())) {
                    return message.getId() != null;
                }
                return true;
            }

            return false;
        }

        private void registerMessages(TransportListener listener, List<Mutable> messages) {
            boolean open;
            synchronized (this) {
                // Check whether it is active and register messages atomically.
                open = isOpen();
                if (open) {
                    for (Mutable message : messages) {
                        registerMessage(message, listener);
                    }
                }
            }
            if (!open) {
                listener.onFailure(new IOException("Unconnected"), messages);
            }
        }

        private void registerMessage(final Message.Mutable message, final TransportListener listener) {
            // Calculate max network delay
            long maxNetworkDelay = getMaxNetworkDelay();
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                Map<String, Object> advice = message.getAdvice();
                if (advice == null) {
                    advice = _advice;
                }
                if (advice != null) {
                    Object timeout = advice.get("timeout");
                    if (timeout instanceof Number) {
                        maxNetworkDelay += ((Number)timeout).intValue();
                    } else if (timeout != null) {
                        maxNetworkDelay += Integer.parseInt(timeout.toString());
                    }
                }
                _connected = true;
            }

            // Schedule a task to expire if the maxNetworkDelay elapses
            final long expiration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + maxNetworkDelay;
            ScheduledFuture<?> task = _scheduler.schedule(() -> {
                long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                long delay = now - expiration;
                if (LOGGER.isDebugEnabled()) {
                    // TODO: make the max delay a parameter ?
                    if (delay > 5000) {
                        LOGGER.debug("Message {} expired {} ms too late", message, delay);
                    }
                    LOGGER.debug("Expiring message {}", message);
                }
                fail(new TimeoutException(), "Expired");
            }, maxNetworkDelay, TimeUnit.MILLISECONDS);

            // Register the exchange
            // Message responses must have the same messageId as the requests

            WebSocketExchange exchange = new WebSocketExchange(message, listener, task);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registering {}", exchange);
            }
            Object existing = _exchanges.put(message.getId(), exchange);
            // Paranoid check
            if (existing != null) {
                throw new IllegalStateException();
            }
        }

        private WebSocketExchange deregisterMessage(Message message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                _connected = false;
            } else if (Channel.META_DISCONNECT.equals(message.getChannel())) {
                _disconnected = true;
            }

            WebSocketExchange exchange = null;
            String messageId = message.getId();
            if (messageId != null) {
                exchange = _exchanges.remove(messageId);
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Deregistering {} for message {}", exchange, message);
            }

            if (exchange != null) {
                exchange.task.cancel(false);
            }

            return exchange;
        }

        protected String trimCloseReason(String reason) {
            if (reason != null) {
                return reason.substring(0, Math.min(reason.length(), MAX_CLOSE_REASON_LENGTH));
            }
            return null;
        }

        protected abstract void send(String content);

        protected void fail(Throwable failure, String reason) {
            disconnect(reason);
            failMessages(failure);
        }

        protected void failMessages(Throwable cause) {
            List<Message.Mutable> messages = new ArrayList<>(1);
            for (WebSocketExchange exchange : new ArrayList<>(_exchanges.values())) {
                Mutable message = exchange.message;
                if (deregisterMessage(message) == exchange) {
                    messages.add(message);
                    exchange.listener.onFailure(cause, messages);
                    messages.clear();
                }
            }
        }

        private void abort(Throwable failure) {
            fail(failure, "Aborted");
        }

        private void disconnect(String reason) {
            if (detach()) {
                shutdown(reason);
            }
        }

        private boolean isAttached() {
            synchronized (AbstractWebSocketTransport.this) {
                return this == _delegate;
            }
        }

        private boolean detach() {
            synchronized (AbstractWebSocketTransport.this) {
                boolean attached = this == _delegate;
                if (attached) {
                    _delegate = null;
                }
                return attached;
            }
        }

        protected abstract boolean isOpen();

        protected abstract void close();

        protected abstract void shutdown(String reason);

        private void terminate() {
            fail(new EOFException(), "Terminate");
        }
    }

    private static class WebSocketExchange {
        private final Mutable message;
        private final TransportListener listener;
        private final ScheduledFuture<?> task;

        public WebSocketExchange(Mutable message, TransportListener listener, ScheduledFuture<?> task) {
            this.message = message;
            this.listener = listener;
            this.task = task;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " " + message;
        }
    }

    private static class WebSocketTransportScheduler extends ScheduledThreadPoolExecutor {
        public WebSocketTransportScheduler(int threads) {
            super(threads);
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            setRemoveOnCancelPolicy(true);
        }
    }
}
