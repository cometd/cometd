/*
 * Copyright (c) 2008-2017 the original author or authors.
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
package org.cometd.websocket.server.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketTransport<S> extends AbstractServerTransport {
    public static final String NAME = "websocket";
    public static final String PREFIX = "ws";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String MESSAGES_PER_FRAME_OPTION = "messagesPerFrame";
    public static final String BUFFER_SIZE_OPTION = "bufferSize";
    public static final String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public static final String COMETD_URL_MAPPING_OPTION = "cometdURLMapping";
    public static final String REQUIRE_HANDSHAKE_PER_CONNECTION_OPTION = "requireHandshakePerConnection";
    public static final String ENABLE_EXTENSION_PREFIX_OPTION = "enableExtension.";

    private final ThreadLocal<BayeuxContext> _bayeuxContext = new ThreadLocal<>();
    private ScheduledExecutorService _scheduler;
    private String _protocol;
    private int _messagesPerFrame;
    private boolean _requireHandshakePerConnection;

    protected AbstractWebSocketTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        _scheduler = newScheduledExecutor();
        _protocol = getOption(PROTOCOL_OPTION, null);
        _messagesPerFrame = getOption(MESSAGES_PER_FRAME_OPTION, 1);
        _requireHandshakePerConnection = getOption(REQUIRE_HANDSHAKE_PER_CONNECTION_OPTION, false);
    }

    @Override
    public void destroy() {
        _scheduler.shutdown();
        super.destroy();
    }

    protected ScheduledExecutorService newScheduledExecutor() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    public ScheduledExecutorService getScheduler() {
        return _scheduler;
    }

    public String getProtocol() {
        return _protocol;
    }

    public int getMessagesPerFrame() {
        return _messagesPerFrame;
    }

    protected boolean checkProtocol(List<String> serverProtocols, List<String> clientProtocols) {
        if (serverProtocols.isEmpty()) {
            return true;
        }

        for (String clientProtocol : clientProtocols) {
            if (serverProtocols.contains(clientProtocol)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BayeuxContext getContext() {
        return _bayeuxContext.get();
    }

    protected List<String> normalizeURLMapping(String urlMapping) {
        String[] mappings = urlMapping.split(",");
        List<String> result = new ArrayList<>(mappings.length);
        for (String mapping : mappings) {
            if (mapping.endsWith("/*")) {
                mapping = mapping.substring(0, mapping.length() - 2);
            }
            if (!mapping.startsWith("/")) {
                mapping = "/" + mapping;
            }
            result.add(mapping);
        }
        return result;
    }

    protected void handleJSONParseException(S wsSession, ServerSession session, String json, Throwable exception) {
        _logger.warn("Error parsing JSON: " + json, exception);
    }

    protected void handleException(S wsSession, ServerSession session, Throwable exception) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("", exception);
        }
    }

    protected abstract void send(S wsSession, ServerSession session, String data, Callback callback);

    protected void onClose(int code, String reason) {
    }

    protected abstract class AbstractWebSocketScheduler implements AbstractServerTransport.Scheduler {
        protected final Logger _logger = LoggerFactory.getLogger(getClass());
        private final Flusher flusher = new Flusher();
        private final BayeuxContext _context;
        private volatile ServerSessionImpl _session;
        private ServerMessage.Mutable _connectReply;
        private ScheduledFuture<?> _connectTask;

        protected AbstractWebSocketScheduler(BayeuxContext context) {
            _context = context;
        }

        protected void send(S wsSession, List<? extends ServerMessage> messages, int batchSize, Callback callback) {
            if (messages.isEmpty()) {
                callback.succeeded();
                return;
            }

            int size = messages.size();
            int batch = Math.min(batchSize, size);
            // Assume 4 fields of 48 chars per message
            int capacity = batch * 4 * 48;
            StringBuilder builder = new StringBuilder(capacity);
            builder.append("[");
            if (batch == 1) {
                // Common path.
                ServerMessage serverMessage = messages.remove(0);
                builder.append(serverMessage.getJSON());
            } else {
                boolean comma = false;
                for (int b = 0; b < batch; ++b) {
                    ServerMessage serverMessage = messages.get(b);
                    if (comma) {
                        builder.append(",");
                    }
                    comma = true;
                    builder.append(serverMessage.getJSON());
                }
                if (batch == size) {
                    messages.clear();
                } else {
                    messages.subList(0, batch).clear();
                }
            }
            builder.append("]");
            AbstractWebSocketTransport.this.send(wsSession, _session, builder.toString(), callback);
        }

        public void onClose(int code, String reason) {
            final ServerSessionImpl session = _session;
            if (session != null) {
                // There is no need to call BayeuxServerImpl.removeServerSession(),
                // because the connection may have been closed for a reload, so
                // just null out the current session to have it retrieved again
                _session = null;
                session.scheduleExpiration(getInterval());
                cancelMetaConnectTask(session);
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Closing {}/{} - {}", code, reason, session);
            }
            AbstractWebSocketTransport.this.onClose(code, reason);
        }

        public void onError(Throwable failure) {
            if (failure instanceof SocketTimeoutException || failure instanceof TimeoutException) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("WebSocket Timeout", failure);
                }
            } else {
                BayeuxContext context = getContext();
                InetSocketAddress address = context == null ? null : context.getRemoteAddress();
                _logger.info("WebSocket Error, Address: " + address, failure);
            }
        }

        protected boolean cancelMetaConnectTask(ServerSessionImpl session) {
            final ScheduledFuture<?> connectTask;
            synchronized (session.getLock()) {
                connectTask = _connectTask;
                _connectTask = null;
            }
            if (connectTask == null) {
                return false;
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Cancelling meta connect task {}", connectTask);
            }
            connectTask.cancel(false);
            return true;
        }

        public void onMessage(S wsSession, String data) {
            _bayeuxContext.set(_context);
            getBayeux().setCurrentTransport(AbstractWebSocketTransport.this);
            try {
                ServerMessage.Mutable[] messages = parseMessages(data);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(wsSession, messages);
                }
            } catch (ParseException x) {
                close(1011, x.toString());
                handleJSONParseException(wsSession, _session, data, x);
            } catch (Throwable x) {
                close(1011, x.toString());
                handleException(wsSession, _session, x);
            } finally {
                _bayeuxContext.set(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        private void processMessages(S wsSession, ServerMessage.Mutable[] messages) throws IOException {
            if (messages.length == 0) {
                throw new IOException();
            }

            ServerSessionImpl session = _session;
            if (session == null) {
                ServerMessage.Mutable message = messages[0];
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    session = getBayeux().newServerSession();
                    session.setAllowMessageDeliveryDuringHandshake(isAllowMessageDeliveryDuringHandshake());
                } else if (!_requireHandshakePerConnection) {
                    _session = session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());
                }
            } else if (session.isDisconnected()) {
                _session = session = null;
            }

            boolean sendQueue = false;
            boolean sendReplies = false;
            boolean scheduleExpiration = false;
            List<ServerMessage.Mutable> replies = new ArrayList<>(messages.length);
            for (ServerMessage.Mutable message : messages) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Processing {}", message);
                }

                switch (message.getChannel()) {
                    case Channel.META_HANDSHAKE: {
                        if (messages.length > 1) {
                            throw new IOException();
                        }
                        ServerMessage.Mutable reply = processMetaHandshake(session, message);
                        if (reply.isSuccessful()) {
                            _session = session;
                        }
                        reply = processReply(session, reply);
                        if (reply != null) {
                            replies.add(reply);
                        }
                        sendQueue = allowMessageDeliveryDuringHandshake(session) && reply != null && reply.isSuccessful();
                        sendReplies = reply != null;
                        scheduleExpiration = true;
                        break;
                    }
                    case Channel.META_CONNECT: {
                        ServerMessage.Mutable reply = processMetaConnect(session, message);
                        reply = processReply(session, reply);
                        if (reply != null) {
                            replies.add(reply);
                        }
                        boolean deliver = isMetaConnectDeliveryOnly() || session != null && session.isMetaConnectDeliveryOnly();
                        sendQueue = deliver && reply != null;
                        sendReplies = reply != null;
                        scheduleExpiration = true;
                        break;
                    }
                    default: {
                        ServerMessage.Mutable reply = getBayeux().handle(session, message);
                        reply = processReply(session, reply);
                        if (reply != null) {
                            replies.add(reply);
                        }
                        // Leave sendQueue unchanged.
                        if (reply != null) {
                            sendReplies = true;
                        }
                        // Leave scheduleExpiration unchanged.
                        break;
                    }
                }
            }

            if (sendQueue || sendReplies) {
                send(wsSession, session, sendQueue, scheduleExpiration, replies);
            }
        }

        private ServerMessage.Mutable processMetaHandshake(ServerSessionImpl session, ServerMessage.Mutable message) {
            ServerMessage.Mutable reply = getBayeux().handle(session, message);
            if (reply.isSuccessful()) {
                session.setScheduler(this);
            }
            return reply;
        }

        private ServerMessage.Mutable processMetaConnect(ServerSessionImpl session, ServerMessage.Mutable message) {
            // Remember the connected status before handling the message.
            boolean wasConnected = session != null && session.isConnected();
            ServerMessage.Mutable reply = getBayeux().handle(session, message);
            if (session != null) {
                if (reply.isSuccessful() && session.isConnected()) {
                    // We need to set the scheduler again, in case the connection
                    // has temporarily broken and we have created a new scheduler.
                    session.setScheduler(this);

                    // If we deliver only via meta connect and we have messages, then reply.
                    boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                    boolean hasMessages = session.hasNonLazyMessages();
                    boolean replyToMetaConnect = hasMessages && metaConnectDelivery;
                    if (!replyToMetaConnect) {
                        long timeout = session.calculateTimeout(getTimeout());
                        boolean holdMetaConnect = timeout > 0 && wasConnected;
                        if (holdMetaConnect) {
                            reply = shouldHoldMetaConnect(session, reply, timeout);
                        }
                    }
                }
                if (reply != null && session.isDisconnected()) {
                    reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                }
            }
            return reply;
        }

        private ServerMessage.Mutable shouldHoldMetaConnect(ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
            // Decide atomically if we need to hold the meta connect or not
            // In schedule() we decide atomically if reply to the meta connect.
            synchronized (session.getLock()) {
                if (!session.hasNonLazyMessages()) {
                    if (cancelMetaConnectTask(session)) {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Cancelled unresponded meta connect {}", _connectReply);
                        }
                    }

                    _connectReply = reply;

                    // Delay the connect reply until timeout.
                    long expiration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + timeout;
                    _connectTask = getScheduler().schedule(new MetaConnectReplyTask(reply, expiration), timeout, TimeUnit.MILLISECONDS);
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Scheduled meta connect {}", _connectTask);
                    }
                    reply = null;
                }
                return reply;
            }
        }

        protected void send(S wsSession, ServerSessionImpl session, boolean sendQueue, boolean scheduleExpiration, List<ServerMessage.Mutable> replies) {
            List<ServerMessage> queue = Collections.emptyList();
            if (sendQueue && session != null) {
                queue = session.takeQueue();
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Sending {}, replies={}, messages={}", session, replies, queue);
            }
            boolean queued = flusher.queue(new Entry<>(wsSession, session, scheduleExpiration, queue, replies));
            if (queued) {
                flusher.iterate();
            }
        }

        protected abstract void close(int code, String reason);

        @Override
        public void cancel() {
            final ServerSessionImpl session = _session;
            if (session != null) {
                if (cancelMetaConnectTask(session)) {
                    close(1000, "Cancel");
                }
            }
        }

        @Override
        public void schedule() {
            // This method may be called concurrently, for example when
            // two clients publish concurrently on the same channel.
            schedule(false, null);
        }

        protected abstract void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply);

        protected void schedule(S wsSession, boolean timeout, ServerMessage.Mutable expiredConnectReply) {
            // This method may be executed concurrently by threads triggered by
            // schedule() and by the timeout thread that replies to the meta connect.

            ServerSessionImpl session = _session;
            try {
                if (session == null) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("No session, skipping reply {}", expiredConnectReply);
                    }
                    return;
                }

                boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Flushing {} timeout={} metaConnectDelivery={}", session, timeout, metaConnectDelivery);
                }

                // Decide atomically if we have to reply to the meta connect
                // We need to guarantee the metaConnectDeliverOnly semantic
                // and allow only one thread to reply to the meta connect
                // otherwise we may have out of order delivery.
                boolean reply = false;
                ServerMessage.Mutable connectReply;
                synchronized (session.getLock()) {
                    connectReply = _connectReply;

                    if (timeout && connectReply != expiredConnectReply) {
                        // We had a second meta connect arrived while we were expiring the first:
                        // just ignore to reply to the first connect as if we were able to cancel it
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Flushing skipped replies that do not match: {} != {}", connectReply, expiredConnectReply);
                        }
                        return;
                    }

                    if (connectReply == null) {
                        if (metaConnectDelivery) {
                            // If we need to deliver only via meta connect, but we
                            // do not have one outstanding, wait until it arrives
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Flushing skipped since metaConnectDelivery={}, metaConnectReply={}", metaConnectDelivery, connectReply);
                            }
                            return;
                        }
                    } else {
                        if (timeout || metaConnectDelivery || !session.isConnected()) {
                            // We will reply to the meta connect, so cancel the timeout task
                            cancelMetaConnectTask(session);
                            _connectReply = null;
                            reply = true;
                        }
                    }
                }

                send(wsSession, session, reply, connectReply);
            } catch (Throwable x) {
                close(1011, x.toString());
                handleException(wsSession, session, x);
            }
        }

        private void send(S wsSession, ServerSessionImpl session, boolean reply, ServerMessage.Mutable connectReply) {
            List<ServerMessage.Mutable> replies = Collections.emptyList();
            if (reply) {
                if (session.isDisconnected() && connectReply != null) {
                    connectReply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                }
                connectReply = processReply(session, connectReply);
                if (connectReply != null) {
                    replies = new ArrayList<>(1);
                    replies.add(connectReply);
                }
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Sending {} metaConnectReply={}", session, connectReply);
            }
            send(wsSession, session, true, reply, replies);
        }

        private class MetaConnectReplyTask implements Runnable {
            private final ServerMessage.Mutable _connectReply;
            private final long _connectExpiration;

            private MetaConnectReplyTask(ServerMessage.Mutable connectReply, long connectExpiration) {
                this._connectReply = connectReply;
                this._connectExpiration = connectExpiration;
            }

            @Override
            public void run() {
                long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                long delay = now - _connectExpiration;
                if (_logger.isDebugEnabled()) {
                    // TODO: make the max delay a parameter ?
                    if (delay > 5000) {
                        _logger.debug("/meta/connect {} expired {} ms too late", _connectReply, delay);
                    }
                }
                // Send the meta connect response after timeout.
                // We *must* execute the next schedule() otherwise
                // the client will timeout the meta connect, so we
                // do not care about flipping the _scheduling field.
                schedule(true, _connectReply);
            }
        }

        private class Flusher extends IteratingCallback {
            private final Queue<Entry<S>> _entries = new ArrayDeque<>();
            private Entry<S> entry;
            private boolean terminated;

            private boolean queue(Entry<S> entry) {
                synchronized (this) {
                    if (!terminated) {
                        return _entries.offer(entry);
                    }
                }
                // If we are terminated, we still need to schedule
                // the expiration so that the session can be swept.
                entry.scheduleExpiration();
                return false;
            }

            @Override
            protected Action process() throws Exception {
                Entry<S> entry;
                synchronized (this) {
                    entry = this.entry = _entries.peek();
                }
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Processing {}", entry);
                }

                if (entry == null) {
                    return Action.IDLE;
                }

                S wsSession = entry._wsSession;

                List<ServerMessage.Mutable> replies = entry._replies;
                List<ServerMessage> queue = entry._queue;
                if (replies.size() > 0) {
                    ServerMessage.Mutable reply = replies.get(0);
                    if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                        if (allowMessageDeliveryDuringHandshake(_session) && !queue.isEmpty()) {
                            reply.put("x-messages", queue.size());
                        }
                        getBayeux().freeze(reply);
                        send(wsSession, replies, 1, this);
                        return Action.SCHEDULED;
                    }
                }

                if (!queue.isEmpty()) {
                    // Under load, it is possible that we have many bayeux messages and
                    // that these would generate a large websocket message that the client
                    // could not handle, so we need to split the messages into batches.
                    int size = queue.size();
                    int messagesPerFrame = getMessagesPerFrame();
                    int batchSize = messagesPerFrame > 0 ? Math.min(messagesPerFrame, size) : size;
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Processing queue, batch size {}: {}", batchSize, queue);
                    }
                    send(wsSession, queue, batchSize, this);
                    return Action.SCHEDULED;
                }

                synchronized (this) {
                    _entries.poll();
                }

                // Start the interval timeout after writing the
                // messages since they may take time to be written.
                entry.scheduleExpiration();

                if (_logger.isDebugEnabled()) {
                    _logger.debug("Processing replies {}", replies);
                }
                for (ServerMessage.Mutable reply : replies) {
                    getBayeux().freeze(reply);
                }
                send(wsSession, replies, replies.size(), this);
                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteFailure(Throwable x) {
                Entry<S> entry;
                synchronized (this) {
                    terminated = true;
                    entry = this.entry;
                }
                if (entry != null) {
                    entry.scheduleExpiration();
                }
            }
        }

        private class Entry<W> {
            private final W _wsSession;
            private final ServerSessionImpl _session;
            private final boolean _scheduleExpiration;
            private final List<ServerMessage> _queue;
            private final List<ServerMessage.Mutable> _replies;

            private Entry(W wsSession, ServerSessionImpl session, boolean scheduleExpiration, List<ServerMessage> queue, List<ServerMessage.Mutable> replies) {
                this._wsSession = wsSession;
                this._session = session;
                this._scheduleExpiration = scheduleExpiration;
                this._queue = queue;
                this._replies = replies;
            }

            private void scheduleExpiration() {
                if (_scheduleExpiration) {
                    if (_session != null) {
                        _session.scheduleExpiration(getInterval());
                    }
                }
            }

            @Override
            public String toString() {
                return String.format("%s@%x[messages=%d,replies=%d]",
                        getClass().getSimpleName(),
                        hashCode(),
                        _queue.size(),
                        _replies.size());
            }
        }
    }
}
