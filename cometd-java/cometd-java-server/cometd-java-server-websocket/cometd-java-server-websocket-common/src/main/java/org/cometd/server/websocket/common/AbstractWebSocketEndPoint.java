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
package org.cometd.server.websocket.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AsyncFoldLeft;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketEndPoint {
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final AutoLock lock = new AutoLock();
    private final Flusher flusher = new Flusher();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AbstractWebSocketTransport _transport;
    private final BayeuxContext _bayeuxContext;
    private ServerSessionImpl _session;

    protected AbstractWebSocketEndPoint(AbstractWebSocketTransport transport, BayeuxContext context) {
        this._transport = transport;
        this._bayeuxContext = context;
    }

    protected abstract void send(ServerSession session, String data, Callback callback);

    public abstract void close(int code, String reason);

    public void onMessage(String data, Promise<Void> p) {
        Promise<Void> promise = Promise.from(p::succeed, failure -> {
            if (_logger.isDebugEnabled()) {
                _logger.debug("", failure);
            }
            close(1011, failure.toString());
            p.fail(failure);
        });

        try {
            ServerMessage.Mutable[] messages = _transport.parseMessages(data);
            if (_logger.isDebugEnabled()) {
                _logger.debug("Parsed {} messages on {}", messages == null ? -1 : messages.length, this);
            }
            if (messages != null) {
                processMessages(messages, promise);
            } else {
                promise.succeed(null);
            }
        } catch (ParseException x) {
            close(1011, x.toString());
            _logger.warn("Error parsing JSON: {} on {}", data, this, x);
            promise.succeed(null);
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    public void onClose(int code, String reason) {
        if (terminated.compareAndSet(false, true)) {
            // There is no need to call BayeuxServerImpl.removeServerSession(),
            // because the connection may have been closed for a reload.
            ServerSessionImpl session = _session;
            if (_logger.isDebugEnabled()) {
                _logger.debug("Closing {}/{} - {} on {}", code, reason, session, this);
            }
            _transport.onClose(code, reason);
        }
    }

    public void onError(Throwable failure) {
        if (terminated.compareAndSet(false, true)) {
            if (failure instanceof SocketTimeoutException || failure instanceof TimeoutException) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("WebSocket timeout on {}", this, failure);
                }
            } else {
                InetSocketAddress address = _bayeuxContext == null ? null : _bayeuxContext.getRemoteAddress();
                if (failure instanceof QuietException) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("WebSocket failure, address {} on {}", address, this, failure);
                    }
                } else {
                    _logger.info("WebSocket failure, address {} on {}", address, this, failure);
                }
            }
        }
    }

    private void processMessages(ServerMessage.Mutable[] messages, Promise<Void> promise) {
        if (messages.length == 0) {
            promise.fail(new IOException("bayeux protocol violation"));
        } else {
            ServerSessionImpl session;
            ServerMessage.Mutable m = messages[0];
            if (Channel.META_HANDSHAKE.equals(m.getChannel())) {
                _session = null;
                session = _transport.getBayeux().newServerSession();
                session.setAllowMessageDeliveryDuringHandshake(_transport.isAllowMessageDeliveryDuringHandshake());
            } else {
                session = _session;
                if (session == null) {
                    if (!_transport.isRequireHandshakePerConnection()) {
                        session = _session = (ServerSessionImpl)_transport.getBayeux().getSession(m.getClientId());
                    }
                } else if (_transport.getBayeux().getSession(session.getId()) == null) {
                    session = _session = null;
                }
            }

            Context context = new Context(session);
            AsyncFoldLeft.run(messages, true, (result, message, loop) -> {
                processMessage(messages, context, (ServerMessageImpl)message, Promise.from(b -> loop.proceed(result && b), loop::fail));
            }, Promise.from(flush -> {
                if (flush) {
                    flush(context, promise);
                } else {
                    promise.succeed(null);
                }
            }, promise::fail));
        }
    }

    private void processMessage(ServerMessage.Mutable[] messages, Context context, ServerMessageImpl message, Promise<Boolean> promise) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Processing {} on {}", message, this);
        }

        message.setServerTransport(_transport);
        message.setBayeuxContext(_bayeuxContext);

        ServerSessionImpl session = context.session;
        if (session != null) {
            session.setServerTransport(_transport);
        }

        String channel = message.getChannel();
        if (Channel.META_HANDSHAKE.equals(channel)) {
            if (messages.length > 1) {
                promise.fail(new IOException("protocol violation"));
            } else {
                if (session != null) {
                    // Always disable the Scheduler, as we don't want
                    // messages to be sent before the handshake reply.
                    session.setScheduler(null);
                }
                processMetaHandshake(context, message, promise);
            }
        } else {
            // If the endpoint changed, we want to install a scheduler for the current endpoint,
            // so that server-side messages can be delivered without waiting for a /meta/connect.
            if (session != null && session.updateServerEndPoint(this)) {
                session.setScheduler(new WebSocketScheduler(context, message, 0));
            }
            if (Channel.META_CONNECT.equals(channel)) {
                processMetaConnect(context, message, Promise.from(proceed -> {
                    if (proceed) {
                        resume(context, message, Promise.from(y -> promise.succeed(true), promise::fail));
                    } else {
                        promise.succeed(false);
                    }
                }, promise::fail));
            } else {
                processMessage(context, message, promise);
            }
        }
    }

    private void processMetaHandshake(Context context, ServerMessage.Mutable message, Promise<Boolean> promise) {
        ServerSessionImpl session = context.session;
        _transport.getBayeux().handle(session, message, Promise.from(reply -> {
            _transport.processReply(session, reply, Promise.from(r -> {
                if (r != null) {
                    context.replies.add(r);
                    if (r.isSuccessful()) {
                        _session = session;
                    }
                }
                context.sendQueue = _transport.allowMessageDeliveryDuringHandshake(session) && r != null && r.isSuccessful();
                context.scheduleExpiration = true;
                promise.succeed(true);
            }, promise::fail));
        }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle, promise, x)));
    }

    private void processMetaConnect(Context context, ServerMessage.Mutable message, Promise<Boolean> promise) {
        ServerSessionImpl session = context.session;
        // Remember the connected status before handling the message.
        boolean wasConnected = session != null && session.isConnected();
        _transport.getBayeux().handle(session, message, Promise.from(reply -> {
            boolean proceed = true;
            if (session != null) {
                boolean maySuspend = !session.shouldSchedule();
                boolean metaConnectDelivery = isMetaConnectDeliveryOnly(session);
                if ((maySuspend || !metaConnectDelivery) && reply.isSuccessful()) {
                    long timeout = session.calculateTimeout(_transport.getTimeout());
                    if (timeout > 0 && wasConnected && session.isConnected()) {
                        AbstractServerTransport.Scheduler scheduler = suspend(context, message, timeout);
                        session.setScheduler(scheduler);
                        proceed = false;
                    }
                }
                if (proceed && session.isDisconnected()) {
                    reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                }
            }
            promise.succeed(proceed);
        }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle, promise, x)));
    }

    private void processMessage(Context context, ServerMessageImpl message, Promise<Boolean> promise) {
        ServerSessionImpl session = context.session;
        _transport.getBayeux().handle(session, message, Promise.from(y ->
                _transport.processReply(session, message.getAssociated(), Promise.from(reply -> {
                    if (reply != null) {
                        context.replies.add(reply);
                    }
                    if (!isMetaConnectDeliveryOnly(session)) {
                        context.sendQueue = true;
                    }
                    // Leave scheduleExpiration unchanged.
                    promise.succeed(true);
                }, promise::fail)), promise::fail));
    }

    private boolean isMetaConnectDeliveryOnly(ServerSessionImpl session) {
        return _transport.isMetaConnectDeliveryOnly() || (session != null && session.isMetaConnectDeliveryOnly());
    }

    private AbstractServerTransport.Scheduler suspend(Context context, ServerMessage.Mutable message, long timeout) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Suspended {} on {}", message, this);
        }
        context.session.notifySuspended(message, timeout);
        return new WebSocketScheduler(context, message, timeout);
    }

    private void resume(Context context, ServerMessage.Mutable message, Promise<Void> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        ServerSessionImpl session = context.session;
        if (session != null) {
            Map<String, Object> advice = session.takeAdvice(_transport);
            if (advice != null) {
                reply.put(Message.ADVICE_FIELD, advice);
            }
            if (session.isDisconnected()) {
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            }
        }
        _transport.processReply(session, reply, Promise.from(r -> {
            if (r != null) {
                context.replies.add(r);
            }
            context.sendQueue = true;
            context.scheduleExpiration = true;
            promise.succeed(null);
        }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle, promise, x)));
    }

    private void scheduleExpirationAndFail(ServerSessionImpl session, long metaConnectCycle, Promise<?> promise, Throwable x) {
        _transport.scheduleExpiration(session, metaConnectCycle);
        promise.fail(x);
    }

    protected void flush(Context context, Promise<Void> promise) {
        List<ServerMessage> msgs = List.of();
        ServerSessionImpl session = context.session;
        if (context.sendQueue && session != null) {
            msgs = session.takeQueue(context.replies);
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("Flushing {}, replies={}, messages={} on {}", session, context.replies, msgs, this);
        }
        List<ServerMessage> messages = msgs;
        boolean queued = flusher.queue(new Entry(context, messages, Promise.from(y -> {
            promise.succeed(null);
            writeComplete(context, messages);
        }, promise::fail)));
        if (queued) {
            flusher.iterate();
        }
    }

    protected void writeComplete(Context context, List<ServerMessage> messages) {
    }

    private String toJSON(ServerMessage message) {
        return _transport.toJSON(message);
    }

    @Override
    public String toString() {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    private class WebSocketScheduler implements AbstractServerTransport.Scheduler, Runnable, Promise<Void> {
        private final Context context;
        private final ServerMessage.Mutable message;
        private final AtomicMarkableReference<Scheduler.Task> taskRef;
        private final AtomicBoolean flushing = new AtomicBoolean();

        public WebSocketScheduler(Context context, ServerMessage.Mutable message, long timeout) {
            this.context = context;
            this.message = message;
            this.taskRef = new AtomicMarkableReference<>(timeout > 0 ? _transport.getBayeux().schedule(this, timeout) : null, true);
            context.metaConnectCycle = _transport.newMetaConnectCycle();
        }

        @Override
        public long getMetaConnectCycle() {
            return context.metaConnectCycle;
        }

        @Override
        public void schedule() {
            ServerSessionImpl session = context.session;
            boolean metaConnectDelivery = isMetaConnectDeliveryOnly(session);
            // When delivering only via /meta/connect, we want to behave similarly to HTTP.
            // Otherwise, the scheduler is not "disabled" by cancelling the
            // timeout, and it will continue to deliver messages to the client.
            if (metaConnectDelivery || session.isTerminated()) {
                if (cancelTimeout(false)) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Resuming suspended {} for {} on {}", message, session, AbstractWebSocketEndPoint.this);
                    }
                    session.notifyResumed(message, false);
                    resume(context, message, this);
                }
            } else {
                // Avoid sending messages if this scheduler has been disabled, so that the
                // messages remain in the session queue until the next scheduler is set.
                if (taskRef.isMarked()) {
                    Context ctx = new Context(session);
                    ctx.sendQueue = true;
                    ctx.metaConnectCycle = context.metaConnectCycle;
                    flush(ctx);
                }
            }
        }

        private void flush(Context context) {
            // This method may be called concurrently, for example when
            // two clients publish concurrently on the same channel.
            // We must avoid to dispatch multiple times, to save threads.
            if (flushing.compareAndSet(false, true)) {
                executeFlush(context, Promise.from(y -> {
                    // A thread may have seen flushing=true exactly
                    // when this thread is executing this promise:
                    // re-check whether there are messages to send.
                    flushing.set(false);
                    if (context.session.hasNonLazyMessages()) {
                        flush(context);
                    }
                }, this::fail));
            }
        }

        private void executeFlush(Context context, Promise<Void> promise) {
            _transport.getBayeux().execute(() -> AbstractWebSocketEndPoint.this.flush(context, promise));
        }

        @Override
        public void cancel() {
            if (cancelTimeout(true)) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Cancelling suspended {} for {} on {}", message, context.session, AbstractWebSocketEndPoint.this);
                }
                _transport.scheduleExpiration(context.session, context.metaConnectCycle);
            }
        }

        @Override
        public void destroy() {
            if (cancelTimeout(true)) {
                close(1000, "Destroy");
            }
        }

        @Override
        public void run() {
            // Executed when the /meta/connect timeout expires.
            if (cancelTimeout(false)) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Timing out suspended {} for {} on {}", message, context.session, AbstractWebSocketEndPoint.this);
                }
                context.session.notifyResumed(message, true);
                resume(context, message, this);
            }
        }

        private boolean cancelTimeout(boolean disable) {
            while (true) {
                Scheduler.Task task = taskRef.getReference();
                boolean enabled = taskRef.isMarked();
                if (taskRef.compareAndSet(task, null, enabled, !disable)) {
                    if (task == null) {
                        return false;
                    }
                    task.cancel();
                    return true;
                }
            }
        }

        @Override
        public void succeed(Void result) {
            executeFlush(context, Promise.from(y -> {}, this::fail));
        }

        @Override
        public void fail(Throwable failure) {
            close(1011, failure.toString());
        }

        @Override
        public String toString() {
            return String.format("%s@%x[cycle=%d,%s@%x]",
                    getClass().getSimpleName(),
                    hashCode(),
                    getMetaConnectCycle(),
                    AbstractWebSocketEndPoint.this.getClass().getSimpleName(),
                    AbstractWebSocketEndPoint.this.hashCode());
        }
    }

    private class Flusher extends IteratingCallback {
        private final Queue<Entry> _entries = new ArrayDeque<>();
        private State _state = State.IDLE;
        private StringBuilder _buffer;
        private Entry _entry;
        private int _messageIndex;
        private int _replyIndex;
        private Throwable _failure;

        private boolean queue(Entry entry) {
            Throwable failure;
            try (AutoLock ignored = lock.lock()) {
                failure = _failure;
                if (failure == null) {
                    return _entries.offer(entry);
                }
            }
            entry.fail(failure);
            return false;
        }

        @Override
        protected Action process() {
            while (true) {
                switch (_state) {
                    case IDLE -> {
                        try (AutoLock ignored = lock.lock()) {
                            _entry = _entries.poll();
                        }
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Processing {} on {}", _entry, AbstractWebSocketEndPoint.this);
                        }
                        if (_entry == null) {
                            return Action.IDLE;
                        }
                        _state = State.HANDSHAKE;
                        _buffer = new StringBuilder(256);
                    }
                    case HANDSHAKE -> {
                        _state = State.MESSAGES;
                        List<ServerMessage.Mutable> replies = _entry._context.replies;
                        if (!replies.isEmpty()) {
                            ServerMessage.Mutable reply = replies.get(0);
                            if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                                if (_logger.isDebugEnabled()) {
                                    _logger.debug("Processing handshake reply {}", reply);
                                }
                                List<ServerMessage> queue = _entry._queue;
                                if (_transport.allowMessageDeliveryDuringHandshake(_session) && !queue.isEmpty()) {
                                    reply.put("x-messages", queue.size());
                                }
                                _transport.getBayeux().freeze(reply);
                                _buffer.setLength(0);
                                _buffer.append("[");
                                _buffer.append(toJSON(reply));
                                _buffer.append("]");
                                ++_replyIndex;
                                AbstractWebSocketEndPoint.this.send(_session, _buffer.toString(), this);
                                return Action.SCHEDULED;
                            }
                        }
                    }
                    case MESSAGES -> {
                        List<ServerMessage> messages = _entry._queue;
                        int size = messages.size();
                        if (_messageIndex < size) {
                            int batchSize = _transport.getMessagesPerFrame();
                            batchSize = batchSize > 0 ? Math.min(batchSize, size) : size;
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Processing messages, batch size {}: {}", batchSize, messages);
                            }
                            _buffer.setLength(0);
                            _buffer.append("[");
                            boolean comma = false;
                            int endIndex = Math.min(size, _messageIndex + batchSize);
                            while (_messageIndex < endIndex) {
                                ServerMessage message = messages.get(_messageIndex);
                                if (comma) {
                                    _buffer.append(",");
                                }
                                comma = true;
                                _buffer.append(toJSON(message));
                                ++_messageIndex;
                            }
                            _buffer.append("]");
                            AbstractWebSocketEndPoint.this.send(_session, _buffer.toString(), this);
                            return Action.SCHEDULED;
                        }
                        // Start the interval timeout after writing the
                        // messages since they may take time to be written.
                        _entry.scheduleExpiration();
                        _state = State.REPLIES;
                    }
                    case REPLIES -> {
                        List<ServerMessage.Mutable> replies = _entry._context.replies;
                        int size = replies.size();
                        if (_replyIndex < size) {
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Processing replies {}", replies);
                            }
                            _buffer.setLength(0);
                            _buffer.append("[");
                            boolean comma = false;
                            while (_replyIndex < size) {
                                ServerMessage.Mutable reply = replies.get(_replyIndex);
                                _transport.getBayeux().freeze(reply);
                                if (comma) {
                                    _buffer.append(",");
                                }
                                comma = true;
                                _buffer.append(toJSON(reply));
                                ++_replyIndex;
                            }
                            _buffer.append("]");
                            AbstractWebSocketEndPoint.this.send(_session, _buffer.toString(), this);
                            return Action.SCHEDULED;
                        }
                        _state = State.COMPLETE;
                    }
                    case COMPLETE -> {
                        Entry entry = _entry;
                        _state = State.IDLE;
                        // Do not keep the buffer around while we are idle.
                        _buffer = null;
                        _entry = null;
                        _messageIndex = 0;
                        _replyIndex = 0;
                        entry._promise.succeed(null);
                    }
                    default -> {
                        throw new IllegalStateException("Invalid state " + _state);
                    }
                }
            }
        }

        @Override
        protected void onCompleteFailure(Throwable x) {
            List<Entry> entries;
            try (AutoLock ignored = lock.lock()) {
                _failure = x;
                entries = new ArrayList<>(_entries.size() + 1);
                if (_entry != null) {
                    entries.add(_entry);
                    _entry = null;
                }
                entries.addAll(_entries);
                _entries.clear();
            }
            entries.forEach(e -> e.fail(x));
        }
    }

    private class Entry {
        private final Context _context;
        private final List<ServerMessage> _queue;
        private final Promise<Void> _promise;

        private Entry(Context context, List<ServerMessage> queue, Promise<Void> promise) {
            this._context = context;
            this._queue = queue;
            this._promise = promise;
        }

        private void fail(Throwable failure) {
            // Schedule the expiration so that the session can be swept.
            scheduleExpiration();
            _promise.fail(failure);
        }

        private void scheduleExpiration() {
            if (_context.scheduleExpiration) {
                _transport.scheduleExpiration(_context.session, _context.metaConnectCycle);
            }
        }

        @Override
        public String toString() {
            return String.format("%s@%x[messages=%d,replies=%d]",
                    getClass().getSimpleName(),
                    hashCode(),
                    _queue.size(),
                    _context.replies.size());
        }
    }

    private enum State {
        IDLE, HANDSHAKE, MESSAGES, REPLIES, COMPLETE
    }

    public static class Context {
        private final List<ServerMessage.Mutable> replies = new ArrayList<>();
        private final ServerSessionImpl session;
        private boolean sendQueue;
        private boolean scheduleExpiration;
        private long metaConnectCycle;

        private Context(ServerSessionImpl session) {
            this.session = session;
        }
    }
}
