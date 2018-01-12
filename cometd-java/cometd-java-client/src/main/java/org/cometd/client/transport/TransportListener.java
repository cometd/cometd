/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.util.List;

import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;

/**
 * <p>Abstracts the communication between {@link BayeuxClient} and {@link ClientTransport}.</p>
 * <p>A {@link TransportListener} is associated to every batch of messages being sent,
 * and notified when responses for those messages come back, or a failure occurs.</p>
 *
 * @see MessageClientTransport
 */
public interface TransportListener {
    /**
     * Callback method invoked when the batch of messages is being sent.
     *
     * @param messages the batch of messages being sent
     */
    void onSending(List<? extends Message> messages);

    /**
     * Callback method invoked when a batch of message is received.
     *
     * @param messages the batch of messages received
     */
    void onMessages(List<Message.Mutable> messages);

    /**
     * Callback method invoked when a failure to send or receive messages occurs.
     *
     * @param failure  the failure occurred
     * @param messages the batch of messages being sent
     */
    void onFailure(Throwable failure, List<? extends Message> messages);

    public static class Empty implements TransportListener {
        @Override
        public void onSending(List<? extends Message> messages) {
        }

        @Override
        public void onMessages(List<Message.Mutable> messages) {
        }

        @Override
        public void onFailure(Throwable failure, List<? extends Message> messages) {
        }
    }
}
