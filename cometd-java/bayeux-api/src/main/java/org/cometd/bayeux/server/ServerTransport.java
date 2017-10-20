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
package org.cometd.bayeux.server;

import org.cometd.bayeux.Transport;

/**
 * <p>Server side extension of a Bayeux transport.</p>
 * <p>A {@link ServerTransport} can be configured with a {@link #getTimeout() timeout}
 * (that in the default long polling http transport is the period of time
 * the server waits before answering to a long poll), an {@link #getInterval() interval}
 * (that in the default long polling http transport is the period of time
 * that the client waits between long polls), a {@link #getMaxInterval() maximum interval}
 * (that in the default long polling http transport is the period of time
 * that must elapse before the server consider the client being lost).</p>
 * <p>Further configuration include the {@link #getMaxLazyTimeout() maximum lazy timeout}
 * used for {@link ServerMessage#isLazy() lazy messages} and the style of delivery,
 * that may happen during both responses to requests and via the "/meta/connect" channel,
 * or via the "/meta/connect" channel {@link #isMetaConnectDeliveryOnly() exclusively}.
 */
public interface ServerTransport extends Transport {
    /**
     * @return the timeout (in milliseconds) of this transport
     */
    public long getTimeout();

    /**
     * @return the interval of time (in milliseconds) of this transport
     */
    public long getInterval();

    /**
     * @return the maximum interval of time (in milliseconds) before the server consider the client lost
     */
    public long getMaxInterval();

    /**
     * @return the maximum time (in milliseconds) before dispatching lazy messages
     */
    public long getMaxLazyTimeout();

    /**
     * @return whether the messages are delivered to clients exclusively via the "/meta/connect" channel
     */
    public boolean isMetaConnectDeliveryOnly();

    /**
     * @return The current transport context or null if no current context
     */
    public BayeuxContext getContext();
}
