/*
 * Copyright (c) 2008-2016 the original author or authors.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.UnsupportedCharsetException;
import java.text.ParseException;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.common.AbstractTransport;
import org.cometd.common.JSONContext;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    protected final Logger _logger = LoggerFactory.getLogger(getClass().getName());
    private final BayeuxServerImpl _bayeux;
    private long _interval = 0;
    private long _maxInterval = 10000;
    private long _timeout = 30000;
    private long _maxLazyTimeout = 5000;
    private boolean _metaConnectDeliveryOnly = false;
    private JSONContext.Server jsonContext;
    private boolean _handshakeReconnect;
    private boolean _allowHandshakeDelivery;

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

    public Object getAdvice() {
        return null;
    }

    /**
     * @return the interval in milliseconds
     */
    public long getInterval() {
        return _interval;
    }

    /**
     * @return the maxInterval in milliseconds
     */
    public long getMaxInterval() {
        return _maxInterval;
    }

    /**
     * @return the max lazy timeout in milliseconds before flushing lazy messages
     */
    public long getMaxLazyTimeout() {
        return _maxLazyTimeout;
    }

    /**
     * @return the timeout in milliseconds
     */
    public long getTimeout() {
        return _timeout;
    }

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

    /**
     * Initializes the transport, resolving default and direct options.
     */
    public void init() {
        _interval = getOption(INTERVAL_OPTION, _interval);
        _maxInterval = getOption(MAX_INTERVAL_OPTION, _maxInterval);
        _timeout = getOption(TIMEOUT_OPTION, _timeout);
        _maxLazyTimeout = getOption(MAX_LAZY_TIMEOUT_OPTION, _maxLazyTimeout);
        _metaConnectDeliveryOnly = getOption(META_CONNECT_DELIVERY_OPTION, _metaConnectDeliveryOnly);
        jsonContext = (JSONContext.Server)getOption(JSON_CONTEXT_OPTION);
        _handshakeReconnect = getOption(HANDSHAKE_RECONNECT_OPTION, false);
        _allowHandshakeDelivery = getOption(ALLOW_MESSAGE_DELIVERY_DURING_HANDSHAKE, false);
    }

    public void destroy() {
    }

    protected ServerMessage.Mutable[] parseMessages(BufferedReader reader, boolean jsonDebug) throws ParseException, IOException {
        if (jsonDebug) {
            return parseMessages(IO.toString(reader));
        } else {
            return jsonContext.parse(reader);
        }
    }

    protected ServerMessage.Mutable[] parseMessages(String json) throws ParseException {
        return jsonContext.parse(json);
    }

    /**
     * @return the BayeuxServer object
     */
    public BayeuxServerImpl getBayeux() {
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

    protected ServerMessage.Mutable processReply(ServerSessionImpl session, ServerMessage.Mutable reply) {
        if (reply != null) {
            reply = getBayeux().extendReply(session, session, reply);
        }
        return reply;
    }

    protected byte[] toJSONBytes(ServerMessage message, String encoding) {
        try {
            byte[] bytes = null;
            if (message instanceof ServerMessageImpl) {
                bytes = ((ServerMessageImpl)message).getJSONBytes();
            }
            if (bytes == null) {
                bytes = message.getJSON().getBytes(encoding);
            }
            return bytes;
        } catch (UnsupportedEncodingException x) {
            throw new UnsupportedCharsetException(encoding);
        }
    }

    protected boolean allowMessageDeliveryDuringHandshake(ServerSessionImpl session) {
        return (session != null && session.isAllowMessageDeliveryDuringHandshake()) ||
                isAllowMessageDeliveryDuringHandshake();
    }

    @Override
    public String dump() {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        ContainerLifeCycle.dumpObject(out, this);
    }

    @Override
    public String toString() {
        return getName();
    }

    public interface Scheduler {
        void cancel();

        void schedule();
    }
}
