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
package org.cometd.server.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.server.spi.CometDCookie;
import org.cometd.server.spi.CometDException;
import org.cometd.server.spi.CometDRequest;
import org.cometd.server.spi.CometDResponse;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AsyncFoldLeft;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.thread.Scheduler.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HTTP ServerTransport base class, used by ServerTransports that use
 * HTTP as transport or to initiate a transport connection.</p>
 */
public abstract class AbstractHttpTransport extends AbstractServerTransport {
    public final static String PREFIX = "long-polling";
    public static final String JSON_DEBUG_OPTION = "jsonDebug";
    public static final String MESSAGE_PARAM = "message";
    public final static String BROWSER_COOKIE_NAME_OPTION = "browserCookieName";
    public final static String BROWSER_COOKIE_DOMAIN_OPTION = "browserCookieDomain";
    public final static String BROWSER_COOKIE_PATH_OPTION = "browserCookiePath";
    public final static String BROWSER_COOKIE_SECURE_OPTION = "browserCookieSecure";
    public final static String BROWSER_COOKIE_HTTP_ONLY_OPTION = "browserCookieHttpOnly";
    public final static String BROWSER_COOKIE_SAME_SITE_OPTION = "browserCookieSameSite";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION = "maxSessionsPerBrowser";
    public final static String HTTP2_MAX_SESSIONS_PER_BROWSER_OPTION = "http2MaxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION = "multiSessionInterval";
    public final static String TRUST_CLIENT_SESSION_OPTION = "trustClientSession";
    public final static String DUPLICATE_META_CONNECT_HTTP_RESPONSE_CODE_OPTION = "duplicateMetaConnectHttpResponseCode";
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHttpTransport.class);

    private final ConcurrentMap<String, Collection<ServerSessionImpl>> _sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> _browserMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> _browserSweep = new ConcurrentHashMap<>();
    private String _browserCookieName;
    private String _browserCookieDomain;
    private String _browserCookiePath;
    private boolean _browserCookieSecure;
    private boolean _browserCookieHttpOnly;
    private String _browserCookieSameSite;
    private int _maxSessionsPerBrowser;
    private int _http2MaxSessionsPerBrowser;
    private long _multiSessionInterval;
    private boolean _trustClientSession;
    private int _duplicateMetaConnectHttpResponseCode;
    private long _lastSweep;

    protected AbstractHttpTransport(BayeuxServerImpl bayeux, String name) {
        super(bayeux, name);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init() {
        super.init();
        _browserCookieName = getOption(BROWSER_COOKIE_NAME_OPTION, "BAYEUX_BROWSER");
        _browserCookieDomain = getOption(BROWSER_COOKIE_DOMAIN_OPTION, null);
        _browserCookiePath = getOption(BROWSER_COOKIE_PATH_OPTION, "/");
        _browserCookieSecure = getOption(BROWSER_COOKIE_SECURE_OPTION, false);
        _browserCookieHttpOnly = getOption(BROWSER_COOKIE_HTTP_ONLY_OPTION, true);
        _browserCookieSameSite = getOption(BROWSER_COOKIE_SAME_SITE_OPTION, null);
        _maxSessionsPerBrowser = getOption(MAX_SESSIONS_PER_BROWSER_OPTION, 1);
        _http2MaxSessionsPerBrowser = getOption(HTTP2_MAX_SESSIONS_PER_BROWSER_OPTION, -1);
        _multiSessionInterval = getOption(MULTI_SESSION_INTERVAL_OPTION, 2000);
        _trustClientSession = getOption(TRUST_CLIENT_SESSION_OPTION, false);
        _duplicateMetaConnectHttpResponseCode = getOption(DUPLICATE_META_CONNECT_HTTP_RESPONSE_CODE_OPTION, CometDResponse.SC_INTERNAL_SERVER_ERROR);
        if (_duplicateMetaConnectHttpResponseCode < 400) {
            throw new IllegalArgumentException("Option '" + DUPLICATE_META_CONNECT_HTTP_RESPONSE_CODE_OPTION +
                    "' must be greater or equal to 400, not " + _duplicateMetaConnectHttpResponseCode);
        }
    }

    protected long getMultiSessionInterval() {
        return _multiSessionInterval;
    }

    protected int getDuplicateMetaConnectHttpResponseCode() {
        return _duplicateMetaConnectHttpResponseCode;
    }

    public abstract boolean accept(CometDRequest request);

    public abstract void handle(BayeuxContext bayeuxContext, CometDRequest request, CometDResponse response, Promise<Void> promise) throws IOException, CometDException;

    protected abstract HttpScheduler suspend(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout);

    protected abstract void write(Context context, List<ServerMessage> messages, Promise<Void> promise);

    protected void processMessages(Context context, List<ServerMessage.Mutable> messages, Promise<Void> promise) {
        if (messages.isEmpty()) {
            promise.fail(new IOException("protocol violation"));
        } else {
            Collection<ServerSessionImpl> sessions = findCurrentSessions(context.request());
            ServerMessage.Mutable message = messages.get(0);
            ServerSessionImpl session = findSession(sessions, message);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Processing {} messages for {}", messages.size(), session);
            }
            boolean batch = session != null && !Channel.META_CONNECT.equals(message.getChannel());
            if (batch) {
                session.startBatch();
            }

            context.messages(messages);
            context.session(session);
            AsyncFoldLeft.run(messages, null, (result, item, loop) -> processMessage(context, (ServerMessageImpl)item, Promise.from(loop::proceed, loop::fail)), Promise.complete((r, x) -> {
                if (x == null) {
                    flush(context, promise);
                } else {
                    promise.fail(x);
                }
                if (batch) {
                    session.endBatch();
                }
            }));
        }
    }

    private void processMessage(Context context, ServerMessageImpl message, Promise<Void> promise) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Processing {}", message);
        }

        message.setServerTransport(this);
        message.setBayeuxContext(context.bayeuxContext());
        ServerSessionImpl session = context.session();
        if (session != null) {
            session.setServerTransport(this);
        }

        String channel = message.getChannel();
        if (Channel.META_HANDSHAKE.equals(channel)) {
            if (context.messages().size() > 1) {
                promise.fail(new IOException("bayeux protocol violation"));
            } else {
                processMetaHandshake(context, message, promise);
            }
        } else if (Channel.META_CONNECT.equals(channel)) {
            boolean canSuspend = context.messages().size() == 1;
            processMetaConnect(context, message, canSuspend, Promise.from(y -> resume(context, message, promise), promise::fail));
        } else {
            processMessage1(context, message, promise);
        }
    }

    protected ServerSessionImpl findSession(Collection<ServerSessionImpl> sessions, ServerMessage.Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            ServerSessionImpl session = getBayeux().newServerSession();
            session.setAllowMessageDeliveryDuringHandshake(isAllowMessageDeliveryDuringHandshake());
            return session;
        }

        // Is there an existing, trusted, session ?
        String clientId = message.getClientId();
        if (sessions != null) {
            if (clientId != null) {
                for (ServerSessionImpl session : sessions) {
                    if (session.getId().equals(clientId)) {
                        return session;
                    }
                }
            }
        }

        if (_trustClientSession) {
            return (ServerSessionImpl)getBayeux().getSession(clientId);
        }

        return null;
    }

    protected Collection<ServerSessionImpl> findCurrentSessions(CometDRequest request) {
        CometDCookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (CometDCookie cookie : cookies) {
                if (_browserCookieName.equals(cookie.getName())) {
                    return _sessions.get(cookie.getValue());
                }
            }
        }
        return null;
    }

    private void processMetaHandshake(Context context, ServerMessage.Mutable message, Promise<Void> promise) {
        handleMessage(context, message, Promise.from(reply -> {
            ServerSessionImpl session = context.session();
            if (reply.isSuccessful()) {
                String id = findBrowserId(context);
                if (id == null) {
                    id = setBrowserId(context);
                }
                String browserId = id;
                session.setBrowserId(browserId);

                Collection<ServerSessionImpl> sessions = _sessions.computeIfAbsent(browserId, k -> new CopyOnWriteArrayList<>());
                sessions.add(session);

                session.addListener((ServerSession.RemovedListener)(s, m, t) -> {
                    _sessions.computeIfPresent(browserId, (k, v) -> {
                        v.remove(session);
                        return v.isEmpty() ? null : v;
                    });
                });
            }
            processReply(session, reply, Promise.from(r -> {
                if (r != null) {
                    context.replies().add(r);
                }
                context.sendQueue(r != null && r.isSuccessful() && allowMessageDeliveryDuringHandshake(session));
                context.scheduleExpiration(true);
                promise.succeed(null);
            }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle(), promise, x)));
        }, promise::fail));
    }

    private void processMetaConnect(Context context, ServerMessage.Mutable message, boolean canSuspend, Promise<Void> promise) {
        ServerSessionImpl session = context.session();
        if (session != null) {
            // Cancel the previous scheduler to cancel any prior waiting /meta/connect.
            session.setScheduler(null);
        }
        // Remember the connected status before handling the message.
        boolean wasConnected = session != null && session.isConnected();
        handleMessage(context, message, Promise.from(reply -> {
            boolean proceed = true;
            if (session != null) {
                boolean maySuspend = !session.shouldSchedule();
                if (canSuspend && maySuspend && reply.isSuccessful()) {
                    CometDRequest request = context.request();
                    // Detect if we have multiple sessions from the same browser.
                    boolean allowSuspendConnect = incBrowserId(session, isHTTP2(request));
                    if (allowSuspendConnect) {
                        long timeout = session.calculateTimeout(getTimeout());
                        // Support old clients that do not send advice:{timeout:0} on the first connect
                        if (timeout > 0 && wasConnected && session.isConnected()) {
                            // Between the last time we checked for messages in the queue
                            // (which was false, otherwise we would not be in this branch)
                            // and now, messages may have been added to the queue.
                            // We will suspend anyway, but setting the scheduler on the
                            // session will decide atomically if we need to resume or not.

                            HttpScheduler scheduler = suspend(context, promise, message, timeout);
                            // Setting the scheduler may resume the /meta/connect
                            session.setScheduler(scheduler);
                            proceed = false;
                        } else {
                            decBrowserId(session, isHTTP2(request));
                        }
                    } else {
                        // There are multiple sessions from the same browser
                        Map<String, Object> advice = reply.getAdvice(true);
                        advice.put("multiple-clients", true);

                        long multiSessionInterval = getMultiSessionInterval();
                        if (multiSessionInterval > 0) {
                            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
                            advice.put(Message.INTERVAL_FIELD, multiSessionInterval);
                        } else {
                            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                            reply.setSuccessful(false);
                        }
                    }
                }
                if (proceed && session.isDisconnected()) {
                    reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                }
            }
            if (proceed) {
                promise.succeed(null);
            }
        }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle(), promise, x)));
    }

    private void processMessage1(Context context, ServerMessageImpl message, Promise<Void> promise) {
        handleMessage(context, message, Promise.from(y -> {
            ServerSessionImpl session = context.session();
            processReply(session, message.getAssociated(), Promise.from(reply -> {
                if (reply != null) {
                    context.replies().add(reply);
                }
                boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session != null && session.isMetaConnectDeliveryOnly();
                if (!metaConnectDelivery) {
                    context.sendQueue(true);
                }
                // Leave scheduleExpiration unchanged.
                promise.succeed(null);
            }, promise::fail));
        }, promise::fail));
    }

    protected boolean isHTTP2(CometDRequest request) {
        return "HTTP/2.0".equals(request.getProtocol());
    }

    protected void flush(Context context, Promise<Void> promise) {
        List<ServerMessage> messages = List.of();
        ServerSessionImpl session = context.session();
        if (context.sendQueue() && session != null) {
            messages = session.takeQueue(context.replies());
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Flushing {}, replies={}, messages={}", session, context.replies(), messages);
        }
        write(context, messages, promise);
    }

    protected void resume(Context context, ServerMessage.Mutable message, Promise<Void> promise) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Resumed {}", message);
        }

        ServerMessage.Mutable reply = message.getAssociated();
        ServerSessionImpl session = context.session();
        if (session != null) {
            Map<String, Object> advice = session.takeAdvice(this);
            if (advice != null) {
                reply.put(Message.ADVICE_FIELD, advice);
            }
            if (session.isDisconnected()) {
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            }
        }

        processReply(session, reply, Promise.from(r -> {
            if (r != null) {
                context.replies().add(r);
            }
            context.sendQueue(true);
            context.scheduleExpiration(true);
            promise.succeed(null);
        }, x -> scheduleExpirationAndFail(session, context.metaConnectCycle(), promise, x)));
    }

    private void scheduleExpirationAndFail(ServerSessionImpl session, long metaConnectCycle, Promise<Void> promise, Throwable x) {
        // If there was a valid session, but something went wrong while processing
        // the reply, schedule the expiration so the session will eventually be swept.
        scheduleExpiration(session, metaConnectCycle);
        promise.fail(x);
    }

    protected String findBrowserId(Context context) {
        return context.bayeuxContext().getCookie(_browserCookieName);
    }

    protected String setBrowserId(Context context) {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 16) {
            builder.append(Long.toString(getBayeux().randomLong(), 36));
        }
        builder.setLength(16);
        String browserId = builder.toString();

        // Need to support the SameSite attribute so build the cookie manually.
        builder.setLength(0);
        builder.append(_browserCookieName).append("=").append(browserId);
        if (_browserCookieDomain != null) {
            builder.append("; Domain=").append(_browserCookieDomain);
        }
        if (_browserCookiePath != null) {
            builder.append("; Path=").append(_browserCookiePath);
        }
        if (_browserCookieHttpOnly) {
            builder.append("; HttpOnly");
        }
        if (context.request().isSecure() && _browserCookieSecure) {
            builder.append("; Secure");
        }
        if (_browserCookieSameSite != null) {
            builder.append("; SameSite=").append(_browserCookieSameSite);
        }
        context.response().addHeader("Set-Cookie", builder.toString());

        return browserId;
    }

    /**
     * Increments the count of sessions for the given browser identifier.
     *
     * @param session the session that increments the count
     * @param http2   whether the HTTP protocol is HTTP/2
     * @return true if the count is below the max sessions per browser value.
     * If false is returned, the count is not incremented.
     * @see #decBrowserId(ServerSessionImpl, boolean)
     */
    public boolean incBrowserId(ServerSessionImpl session, boolean http2) {
        int maxSessionsPerBrowser = http2 ? _http2MaxSessionsPerBrowser : _maxSessionsPerBrowser;
        if (maxSessionsPerBrowser < 0) {
            return true;
        } else if (maxSessionsPerBrowser == 0) {
            return false;
        }

        String browserId = session.getBrowserId();
        AtomicInteger count = _browserMap.computeIfAbsent(browserId, k -> new AtomicInteger());

        // Increment
        int sessions = count.incrementAndGet();

        // If was zero, remove from the sweep
        if (sessions == 1) {
            _browserSweep.remove(browserId);
        }

        boolean result = true;
        if (sessions > maxSessionsPerBrowser) {
            sessions = count.decrementAndGet();
            result = false;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("client {} {} sessions for {}", browserId, sessions, session);
        }

        return result;
    }

    public void decBrowserId(ServerSessionImpl session, boolean http2) {
        int maxSessionsPerBrowser = http2 ? _http2MaxSessionsPerBrowser : _maxSessionsPerBrowser;
        String browserId = session.getBrowserId();
        if (maxSessionsPerBrowser <= 0 || browserId == null) {
            return;
        }

        int sessions = -1;
        AtomicInteger count = _browserMap.get(browserId);
        if (count != null) {
            sessions = count.decrementAndGet();
        }

        if (sessions == 0) {
            _browserSweep.put(browserId, new AtomicInteger());
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("client {} {} sessions for {}", browserId, sessions, session);
        }
    }

    protected void handleMessage(Context context, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
        getBayeux().handle(context.session(), message, promise);
    }


    /**
     * Sweeps the transport for old Browser IDs
     */
    @Override
    protected void sweep() {
        long now = System.nanoTime();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(now - _lastSweep);
        if (_lastSweep != 0 && elapsed > 0) {
            // Calculate the maximum sweeps that a browser ID can be 0 as the
            // maximum interval time divided by the sweep period, doubled for safety
            int maxSweeps = (int)(2 * getMaxInterval() / elapsed);
            for (Map.Entry<String, AtomicInteger> entry : _browserSweep.entrySet()) {
                AtomicInteger count = entry.getValue();
                if (count != null && count.incrementAndGet() > maxSweeps) {
                    String key = entry.getKey();
                    if (_browserSweep.remove(key, count)) {
                        _browserMap.computeIfPresent(key, (k, v) -> {
                            if (v.get() == 0) {
                                if (LOGGER.isDebugEnabled()) {
                                    LOGGER.debug("Swept browserId {}", key);
                                }
                                return null;
                            }
                            return v;
                        });
                    }
                }
            }
        }
        _lastSweep = now;
    }

    protected byte[] toJSONBytes(ServerMessage msg) {
        ServerMessageImpl message = (ServerMessageImpl)(msg instanceof ServerMessageImpl ? msg : getBayeux().newMessage(msg));
        byte[] bytes = message.getJSONBytes();
        if (bytes == null) {
            bytes = toJSON(message).getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    /**
     * <p>A {@link Scheduler} for HTTP-based transports.</p>
     */
    public interface HttpScheduler extends Scheduler {
        ServerMessage.Mutable getMessage();
    }

    protected abstract class LongPollScheduler implements Runnable, HttpScheduler {
        private final AtomicReference<Task> task = new AtomicReference<>();
        private final Context context;
        private final Promise<Void> promise;
        private final ServerMessage.Mutable message;

        protected LongPollScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
            this.context = context;
            this.promise = promise;
            this.message = message;
            this.task.set(getBayeux().schedule(this, timeout));
            context.metaConnectCycle(newMetaConnectCycle());
        }

        public Context getContext() {
            return context;
        }

        public Promise<Void> getPromise() {
            return promise;
        }

        @Override
        public ServerMessage.Mutable getMessage() {
            return message;
        }

        @Override
        public long getMetaConnectCycle() {
            return context.metaConnectCycle();
        }

        @Override
        public void schedule() {
            if (cancelTimeout()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Resuming suspended {} for {}", message, context.session());
                }
                resume(false);
            }
        }

        @Override
        public void cancel() {
            if (cancelTimeout()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Cancelling suspended {} for {}", message, context.session());
                }
                error(new TimeoutException());
            }
        }

        @Override
        public void destroy() {
            cancel();
        }

        private boolean cancelTimeout() {
            // Cannot rely on the return value of task.cancel()
            // since it may be invoked when the task is in run()
            // where cancellation is not possible (it's too late).
            Task task = this.task.getAndSet(null);
            if (task == null) {
                return false;
            }
            task.cancel();
            return true;
        }

        @Override
        public void run() {
            if (cancelTimeout()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Timing out suspended {} for {}", message, context.session());
                }
                resume(true);
            }
        }

        private void resume(boolean timeout) {
            decBrowserId(context.session(), isHTTP2(context.request()));
            dispatch(timeout);
        }

        protected abstract void dispatch(boolean timeout);

        protected void error(Throwable failure) {
            CometDRequest request = context.request();
            decBrowserId(context.session(), isHTTP2(request));
            promise.fail(failure);
        }

        @Override
        public String toString() {
            return String.format("%s@%x[cycle=%d]", getClass().getSimpleName(), hashCode(), getMetaConnectCycle());
        }
    }

    // TODO make this back to a top-level class in SPI
    public interface Context {
        BayeuxContext bayeuxContext();

        List<ServerMessage.Mutable> messages();

        void messages(List<ServerMessage.Mutable> messages);

        long metaConnectCycle();

        void metaConnectCycle(long l);

        List<ServerMessage.Mutable> replies();

        CometDRequest request();

        CometDResponse response();

        void scheduleExpiration(boolean b);

        boolean scheduleExpiration();

        AbstractHttpTransport.HttpScheduler scheduler();

        void scheduler(HttpScheduler httpScheduler);

        void sendQueue(boolean b);

        boolean sendQueue();

        ServerSessionImpl session();

        void session(ServerSessionImpl session);
    }
}
