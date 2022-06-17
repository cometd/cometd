/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.Promise;
import org.cometd.client.transport.HttpClientTransport;
import org.cometd.client.transport.MessageClientTransport;
import org.cometd.client.transport.TransportListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketTransport extends HttpClientTransport implements MessageClientTransport {
    public static final String PREFIX = "ws";
    public static final String NAME = "websocket";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String PERMESSAGE_DEFLATE_OPTION = "permessageDeflate";
    public static final String CONNECT_TIMEOUT_OPTION = "connectTimeout";
    public static final String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public static final String STICKY_RECONNECT_OPTION = "stickyReconnect";
    public static final int MAX_CLOSE_REASON_LENGTH = 30;
    public static final int NORMAL_CLOSE_CODE = 1000;
    protected static final String COOKIE_HEADER = "Cookie";
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractWebSocketTransport.class);

    private final Object _lock = this;
    private boolean _open;
    private String _protocol;
    private boolean _perMessageDeflate;
    private long _connectTimeout;
    private long _idleTimeout;
    private boolean _stickyReconnect;
    private Delegate _delegate;
    private TransportListener _listener;

    protected AbstractWebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler) {
        super(NAME, url, options, scheduler);
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
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _perMessageDeflate = getOption(PERMESSAGE_DEFLATE_OPTION, false);
        setMaxNetworkDelay(15000L);
        _connectTimeout = 30000L;
        _idleTimeout = 60000L;
        _stickyReconnect = getOption(STICKY_RECONNECT_OPTION, true);
        locked(() -> {
            _open = true;
            initScheduler();
        });
    }

    protected void locked(Runnable block) {
        locked(() -> {
            block.run();
            return null;
        });
    }

    protected <T> T locked(Supplier<T> block) {
        synchronized (_lock) {
            return block.get();
        }
    }

    public String getProtocol() {
        return _protocol;
    }

    public boolean isPerMessageDeflateEnabled() {
        return _perMessageDeflate;
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
        Delegate delegate = locked(() -> {
            _open = false;
            shutdownScheduler();
            return getDelegate();
        });
        if (delegate != null) {
            delegate.abort(failure);
        }
    }

    @Override
    public void terminate() {
        Delegate delegate = locked(() -> {
            _open = false;
            shutdownScheduler();
            return getDelegate();
        });
        if (delegate != null) {
            delegate.terminate();
        }
        super.terminate();
    }

    protected Delegate getDelegate() {
        return locked(() -> _delegate);
    }

    @Override
    public void send(TransportListener listener, List<Mutable> messages) {
        Delegate delegate = getDelegate();
        if (delegate == null) {
            // Mangle the URL
            String url = getURL();
            url = url.replaceFirst("^http", "ws");

            Delegate newDelegate = connect(url, listener, messages);

            if (newDelegate == null) {
                return;
            }

            delegate = locked(() -> {
                if (_delegate == null) {
                    return _delegate = newDelegate;
                } else {
                    // We connected concurrently, keep only one.
                    newDelegate.shutdown("Extra");
                    return _delegate;
                }
            });
        }

        try {
            delegate.registerMessages(listener, messages);

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

        protected void registerMessages(TransportListener listener, List<Mutable> messages) {
            boolean open = locked(() -> {
                // Check whether it is active and register messages atomically.
                if (isOpen()) {
                    for (Mutable message : messages) {
                        registerMessage(message, listener);
                    }
                    return true;
                }
                return false;
            });
            if (!open) {
                listener.onFailure(new IOException("Unconnected"), messages);
            }
        }

        private void registerMessage(Message.Mutable message, TransportListener listener) {
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

            // Schedule a task to expire if the maxNetworkDelay elapses.
            long delay = maxNetworkDelay;
            AtomicReference<ScheduledFuture<?>> timeoutTaskRef = new AtomicReference<>();
            ScheduledFuture<?> newTask = getScheduler().schedule(() -> onTimeout(listener, message, delay, timeoutTaskRef), maxNetworkDelay, TimeUnit.MILLISECONDS);
            timeoutTaskRef.set(newTask);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Started waiting for message reply, {} ms, task@{}", maxNetworkDelay, Integer.toHexString(newTask.hashCode()));
            }

            // Register the exchange
            // Message responses must have the same messageId as the requests

            WebSocketExchange exchange = new WebSocketExchange(message, listener, timeoutTaskRef);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registering {}", exchange);
            }
            Object existing = _exchanges.put(message.getId(), exchange);
            // Paranoid check
            if (existing != null) {
                throw new IllegalStateException("Could not register exchange " + exchange + ", existing exchange is " + existing + " for message " + message);
            }
        }

        private void onTimeout(TransportListener listener, Message message, long delay, AtomicReference<ScheduledFuture<?>> timeoutTaskRef) {
            listener.onTimeout(Collections.singletonList(message), Promise.from(result -> {
                if (result > 0) {
                    ScheduledFuture<?> newTask = getScheduler().schedule(() -> onTimeout(listener, message, delay + result, timeoutTaskRef), result, TimeUnit.MILLISECONDS);
                    ScheduledFuture<?> oldTask = timeoutTaskRef.getAndSet(newTask);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Extended waiting for message reply, {} ms, oldTask@{}, newTask@{}", result, Integer.toHexString(oldTask.hashCode()), Integer.toHexString(newTask.hashCode()));
                    }
                } else {
                    fail(new TimeoutException("Network delay expired: " + delay + " ms"), "Expired");
                }
            }, failure -> fail(failure, "Failure")));
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
                ScheduledFuture<?> task = exchange.taskRef.get();
                task.cancel(false);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Cancelled waiting for message replies, task@{}", Integer.toHexString(task.hashCode()));
                }
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
            List<Message> messages = new ArrayList<>(1);
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
            return locked(() -> this == _delegate);
        }

        private boolean detach() {
            return locked(() -> {
                boolean attached = this == _delegate;
                if (attached) {
                    _delegate = null;
                }
                return attached;
            });
        }

        protected boolean isOpen() {
            return locked(() -> _open);
        }

        protected abstract void close();

        protected abstract void shutdown(String reason);

        private void terminate() {
            fail(new EOFException(), "Terminate");
        }
    }

    private static class WebSocketExchange {
        private final Mutable message;
        private final TransportListener listener;
        private final AtomicReference<ScheduledFuture<?>> taskRef;

        private WebSocketExchange(Mutable message, TransportListener listener, AtomicReference<ScheduledFuture<?>> taskRef) {
            this.message = message;
            this.listener = listener;
            this.taskRef = taskRef;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " " + message;
        }
    }
}
