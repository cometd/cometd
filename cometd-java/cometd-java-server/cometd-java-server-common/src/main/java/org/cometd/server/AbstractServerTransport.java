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
package org.cometd.server;

import java.io.IOException;
import java.text.ParseException;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.AbstractTransport;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * <p>The base class of all server transports.</p>
 * <p>Each derived Transport class should declare all options that it supports
 * by calling {@link #setOption(String, Object)} for each option.
 * Then during the call the {@link #init()}, each transport should
 * call the variants of {@link #getOption(String)} to obtained the configured
 * value for the option.</p>
 */
public abstract class AbstractServerTransport extends AbstractTransport implements ServerTransport, Dumpable {
    public static final String TIMEOUT_OPTION = "timeout";
    public static final String INTERVAL_OPTION = "interval";
    public static final String MAX_INTERVAL_OPTION = "maxInterval";
    public static final String MAX_PROCESSING_OPTION = "maxProcessing";
    public static final String MAX_LAZY_TIMEOUT_OPTION = "maxLazyTimeout";
    public static final String META_CONNECT_DELIVERY_OPTION = "metaConnectDeliverOnly";
    public static final String MAX_QUEUE_OPTION = "maxQueue";
    public static final String JSON_CONTEXT_OPTION = "jsonContext";
    public static final String HANDSHAKE_RECONNECT_OPTION = "handshakeReconnect";
    public static final String ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE = "allowMessageDeliveryDuringHandshake";
    public static final String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";
    // /meta/connect cycles must be shared because a transport may fallback to another.
    private static final AtomicLong META_CONNECT_CYCLES = new AtomicLong();

    private final BayeuxServerImpl _bayeux;
    private long _interval = 0;
    private long _maxInterval = 10000;
    private long _timeout = 30000;
    private long _maxLazyTimeout = 5000;
    private boolean _metaConnectDeliveryOnly = false;
    private JSONContextServer _jsonContext;
    private boolean _handshakeReconnect;
    private boolean _allowHandshakeDelivery;
    private int _maxMessageSize;

    /**
     * <p>The constructor is passed the {@link BayeuxServerImpl} instance for
     * the transport.  The {@link BayeuxServerImpl#getOptions()} map is
     * populated with the default options known by this transport. The options
     * are then inspected again when {@link #init()} is called, to set the
     * actual values used.  The options are arranged into a naming hierarchy
     * by derived classes adding prefix by calling add {@link #setOptionPrefix(String)}.
     * Calls to {@link #getOption(String)} will use the list of prefixes
     * to search for the most specific option set.</p>
     *
     * @param bayeux the BayeuxServer implementation
     * @param name   the name of the transport
     */
    protected AbstractServerTransport(BayeuxServerImpl bayeux, String name) {
        super(name, bayeux.getOptions());
        _bayeux = bayeux;
    }

    public long newMetaConnectCycle() {
        return META_CONNECT_CYCLES.incrementAndGet();
    }

    /**
     * @return the interval in milliseconds
     */
    @Override
    public long getInterval() {
        return _interval;
    }

    /**
     * @return the maxInterval in milliseconds
     */
    @Override
    public long getMaxInterval() {
        return _maxInterval;
    }

    /**
     * @return the max lazy timeout in milliseconds before flushing lazy messages
     */
    @Override
    public long getMaxLazyTimeout() {
        return _maxLazyTimeout;
    }

    /**
     * @return the timeout in milliseconds
     */
    @Override
    public long getTimeout() {
        return _timeout;
    }

    @Override
    public boolean isMetaConnectDeliveryOnly() {
        return _metaConnectDeliveryOnly;
    }

    public void setMetaConnectDeliveryOnly(boolean meta) {
        _metaConnectDeliveryOnly = meta;
    }

    public boolean isHandshakeReconnect() {
        return _handshakeReconnect;
    }

    public void setHandshakeReconnect(boolean handshakeReconnect) {
        _handshakeReconnect = handshakeReconnect;
    }

    public boolean isAllowMessageDeliveryDuringHandshake() {
        return _allowHandshakeDelivery;
    }

    public void setAllowMessageDeliveryDuringHandshake(boolean allow) {
        _allowHandshakeDelivery = allow;
    }

    public int getMaxMessageSize() {
        return _maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        _maxMessageSize = maxMessageSize;
    }

    /**
     * Initializes the transport, resolving default and direct options.
     */
    public void init() {
        _interval = getOption(INTERVAL_OPTION, _interval);
        _maxInterval = getOption(MAX_INTERVAL_OPTION, _maxInterval);
        _timeout = getOption(TIMEOUT_OPTION, _timeout);
        _maxLazyTimeout = getOption(MAX_LAZY_TIMEOUT_OPTION, _maxLazyTimeout);
        _metaConnectDeliveryOnly = getOption(META_CONNECT_DELIVERY_OPTION, _metaConnectDeliveryOnly);
        _jsonContext = (JSONContextServer)getOption(JSON_CONTEXT_OPTION);
        _handshakeReconnect = getOption(HANDSHAKE_RECONNECT_OPTION, false);
        _allowHandshakeDelivery = getOption(ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, false);
        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, -1);
    }

    public void destroy() {
    }

    protected JSONContextServer getJSONContextServer() {
        return _jsonContext;
    }

    public ServerMessage.Mutable[] parseMessages(String json) throws ParseException {
        return _jsonContext.parse(json);
    }

    /**
     * @return the BayeuxServer object
     */
    public BayeuxServerImpl getBayeuxServer() {
        return _bayeux;
    }

    /**
     * @param interval the interval in milliseconds
     */
    public void setInterval(long interval) {
        _interval = interval;
    }

    /**
     * @param maxInterval the maxInterval in milliseconds
     */
    public void setMaxInterval(long maxInterval) {
        _maxInterval = maxInterval;
    }

    /**
     * @param timeout the timeout in milliseconds
     */
    public void setTimeout(long timeout) {
        _timeout = timeout;
    }

    /**
     * @param maxLazyTimeout the maxLazyTimeout in milliseconds
     */
    public void setMaxLazyTimeout(long maxLazyTimeout) {
        _maxLazyTimeout = maxLazyTimeout;
    }

    /**
     * Housekeeping sweep, called a regular intervals
     */
    protected void sweep() {
    }

    public void processReply(ServerSessionImpl session, ServerMessage.Mutable reply, Promise<ServerMessage.Mutable> promise) {
        getBayeuxServer().extendReply(session, session, reply, promise);
    }

    protected String toJSON(ServerMessage msg) {
        return toJSON((ServerMessageImpl)(msg instanceof ServerMessageImpl ? msg : _bayeux.newMessage(msg)));
    }

    private String toJSON(ServerMessageImpl message) {
        String json = message.getJSON();
        if (json == null) {
            json = _jsonContext.generate(message);
        }
        return json;
    }

    public boolean allowMessageDeliveryDuringHandshake(ServerSessionImpl session) {
        return session != null && session.isAllowMessageDeliveryDuringHandshake();
    }

    public void scheduleExpiration(ServerSessionImpl session, long metaConnectCycle) {
        if (session != null) {
            session.scheduleExpiration(getInterval(), getMaxInterval(), metaConnectCycle);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        Dumpable.dumpObject(out, this);
    }

    @Override
    public String toString() {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), getName());
    }

    /**
     * <p>Performs server-to-client transport operations when a {@code /meta/connect}
     * message is held and a server-side message is published.</p>
     * <p>HTTP transports can only perform server-to-client
     * sends if there is an outstanding {@code /meta/connect},
     * or if they are processing incoming messages.</p>
     * <p>WebSocket transports, on the other hand, can perform
     * server-to-client sends even if there is no outstanding
     * {@code /meta/connect}.</p>
     */
    public interface Scheduler {
        /**
         * @return the cycle number for suspended {@code /meta/connect}s.
         */
        public default long getMetaConnectCycle() {
            return 0;
        }

        /**
         * Invoked when the transport wants to send queued
         * messages, and possibly a /meta/connect reply.
         */
        public default void schedule() {
        }

        /**
         * Invoked when the transport wants to cancel scheduled operations
         * that will trigger when the /meta/connect timeout fires.
         */
        public default void cancel() {
        }

        /**
         * Invoked when the transport wants to abort communication.
         */
        public default void destroy() {
        }

        /**
         * <p>A scheduler that does not perform any operation
         * but remembers the {@code /meta/connect} cycle.</p>
         */
        public static class None implements Scheduler {
            private final long metaConnectCycle;

            public None(long metaConnectCycle) {
                this.metaConnectCycle = metaConnectCycle;
            }

            @Override
            public long getMetaConnectCycle() {
                return metaConnectCycle;
            }

            @Override
            public String toString() {
                return String.format("%s@%x[cycle=%d]", getClass().getSimpleName(), hashCode(), getMetaConnectCycle());
            }
        }
    }
}
