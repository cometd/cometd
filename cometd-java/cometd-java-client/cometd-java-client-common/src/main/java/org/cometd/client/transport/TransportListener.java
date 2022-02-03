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
package org.cometd.client.transport;

import java.util.EventListener;
import java.util.List;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.client.BayeuxClient;

/**
 * <p>Abstracts the communication between {@link BayeuxClient} and {@link ClientTransport}.</p>
 * <p>A {@link TransportListener} is associated to every batch of messages being sent,
 * and notified when responses for those messages come back, or a failure occurs.</p>
 *
 * @see MessageClientTransport
 */
public interface TransportListener extends EventListener {
    /**
     * Callback method invoked when the batch of messages is being sent.
     *
     * @param messages the batch of messages being sent
     */
    default void onSending(List<? extends Message> messages) {
    }

    /**
     * Callback method invoked when a batch of message is received.
     *
     * @param messages the batch of messages received
     */
    default void onMessages(List<Message.Mutable> messages) {
    }

    /**
     * Callback method invoked when a failure to send or receive messages occurs.
     *
     * @param failure  the failure occurred
     * @param messages the batch of messages being sent
     */
    default void onFailure(Throwable failure, List<? extends Message> messages) {
    }

    /**
     * <p>Callback method invoked when the send of a batch of messages expires
     * before receiving a response from the server, controlled by the
     * {@link ClientTransport#MAX_NETWORK_DELAY_OPTION maxNetworkDelay} option.</p>
     * <p>Implementations may extend the wait by succeeding the promise with the
     * number of milliseconds of the extended wait.</p>
     * <p>Succeeding the callback with {@code 0} or a negative value confirms the
     * expiration, which will eventually call {@link #onFailure(Throwable, List)}
     * with a {@link java.util.concurrent.TimeoutException}.</p>
     *
     * @param messages the batch of messages sent
     * @param promise the promise to complete
     */
    default void onTimeout(List<? extends Message> messages, Promise<Long> promise) {
        promise.succeed(0L);
    }

    /**
     * Empty implementation of {@link TransportListener}.
     *
     * @deprecated use {@link TransportListener} directly instead.
     */
    @Deprecated
    public static class Empty implements TransportListener {
    }
}
