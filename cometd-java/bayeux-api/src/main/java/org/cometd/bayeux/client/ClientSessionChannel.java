/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.bayeux.client;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;

/**
 * <p>A client side channel representation.</p>
 * <p>A {@link ClientSessionChannel} is scoped to a particular {@link ClientSession}
 * that is obtained by a call to {@link ClientSession#getChannel(String)}.</p>
 * <p>Typical usage examples are:</p>
 * <pre>
 *     clientSession.getChannel("/foo/bar").subscribe(mySubscriptionListener);
 *     clientSession.getChannel("/foo/bar").publish("Hello");
 *     clientSession.getChannel("/meta/*").addListener(myMetaChannelListener);
 * <pre>
 */
public interface ClientSessionChannel extends Channel
{
    /**
     * @param listener the listener to add
     */
    void addListener(ClientSessionChannelListener listener);

    /**
     * @param listener the listener to remove
     */
    void removeListener(ClientSessionChannelListener listener);

    /**
     * @return the client session associated with this channel
     */
    ClientSession getSession();

    /**
     * Equivalent to {@link #publish(Object, Object) publish(data, null)}.
     * @param data the data to publish
     */
    void publish(Object data);

    /**
     * Publishes the given {@code data} to this channel,
     * optionally specifying the {@code messageId} to set on the
     * publish message.
     * @param data the data to publish
     * @param messageId the message id to set on the message, or null to let the
     * implementation choose the message id.
     * @see Message#getId()
     */
    void publish(Object data, String messageId);

    void subscribe(MessageListener listener);

    void unsubscribe(MessageListener listener);

    void unsubscribe();

    /**
     * <p>Represents a listener on a {@link ClientSessionChannel}.</p>
     * <p>Sub-interfaces specify the exact semantic of the listener.</p>
     */
    interface ClientSessionChannelListener extends Bayeux.BayeuxListener
    {
    }

    /**
     * A listener for messages on a {@link ClientSessionChannel}.
     */
    public interface MessageListener extends ClientSessionChannelListener
    {
        /**
         * Callback invoked when a message is received on the given {@code channel}.
         * @param channel the channel that received the message
         * @param message the message received
         */
        void onMessage(ClientSessionChannel channel, Message message);
    }
}
