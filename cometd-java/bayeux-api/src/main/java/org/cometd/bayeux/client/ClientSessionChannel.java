/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.util.List;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;

/**
 * <p>A client side channel representation.</p>
 * <p>A {@link ClientSessionChannel} is scoped to a particular {@link ClientSession}
 * that is obtained by a call to {@link ClientSession#getChannel(String)}.</p>
 * <p>Typical usage examples are:</p>
 * <pre>
 * clientSession.getChannel("/foo/bar").subscribe(mySubscriptionListener);
 * clientSession.getChannel("/foo/bar").publish("Hello");
 * clientSession.getChannel("/meta/*").addListener(myMetaChannelListener);
 * </pre>
 */
public interface ClientSessionChannel extends Channel {
    /**
     * <p>Adds a listener to this channel.</p>
     * <p>If the listener is a {@link MessageListener}, it will be invoked
     * if a message arrives to this channel.</p>
     * <p>Adding a listener never involves communication with the server,
     * differently from {@link #subscribe(MessageListener)}.</p>
     * <p>Listeners are best suited to receive messages from
     * {@link #isMeta() meta channels}.</p>
     *
     * @param listener the listener to add
     * @see #removeListener(ClientSessionChannelListener)
     */
    public void addListener(ClientSessionChannelListener listener);

    /**
     * <p>Removes the given {@code listener} from this channel.</p>
     * <p>Removing a listener never involves communication with the server,
     * differently from {@link #unsubscribe(MessageListener)}.</p>
     *
     * @param listener the listener to remove
     * @see #addListener(ClientSessionChannelListener)
     */
    public void removeListener(ClientSessionChannelListener listener);

    /**
     * @return an immutable snapshot of the listeners
     * @see #addListener(ClientSessionChannelListener)
     */
    public List<ClientSessionChannelListener> getListeners();

    /**
     * @return the client session associated with this channel
     */
    public ClientSession getSession();

    /**
     * <p>Publishes the given {@code data} onto this channel.</p>
     * <p>The {@code data} published must not be null and can be any object that
     * can be natively converted to JSON (numbers, strings, arrays, lists, maps),
     * or objects for which a JSON converter has been registered with the
     * infrastructure responsible of the JSON conversion.</p>
     *
     * @param data the data to publish
     * @see #publish(Object, ClientSession.MessageListener)
     */
    public default void publish(Object data) {
        publish(data, ClientSession.MessageListener.NOOP);
    }

    /**
     * @param data     the data to publish
     * @param callback the message callback to notify of the publish result
     * @deprecated use {@link #publish(Object, ClientSession.MessageListener)} instead
     */
    @Deprecated
    public default void publish(Object data, MessageListener callback) {
        publish(data, message -> callback.onMessage(this, message));
    }

    /**
     * <p>Publishes the given {@code data} onto this channel, notifying the given
     * {@code callback} of the publish result, whether successful or unsuccessful.</p>
     *
     * @param data     the data to publish
     * @param callback the message callback to notify of the publish result
     * @see #publish(Object)
     * @see #publish(Message.Mutable, ClientSession.MessageListener)
     */
    public void publish(Object data, ClientSession.MessageListener callback);

    /**
     * @param message  the message to publish
     * @param callback the message callback to notify of the publish result
     * @deprecated use {@link #publish(Message.Mutable, ClientSession.MessageListener)} instead
     */
    @Deprecated
    public default void publish(Message.Mutable message, MessageListener callback) {
        publish(message, m -> callback.onMessage(this, m));
    }

    /**
     * <p>Publishes the given {@code message} onto this channel, notifying the
     * given {@code callback} of the publish result.</p>
     *
     * @param message  the message to publish
     * @param callback the message callback to notify of the publish result
     * @see #publish(Object, ClientSession.MessageListener)
     */
    public void publish(Message.Mutable message, ClientSession.MessageListener callback);

    /**
     * <p>Equivalent to {@link #subscribe(ClientSessionChannel.MessageListener, ClientSession.MessageListener)
     * subscribe(listener, null)}.</p>
     *
     * @param listener the listener to register and invoke when a message arrives on this channel.
     * @return whether the subscribe message has been sent
     */
    public default boolean subscribe(MessageListener listener) {
        return subscribe(listener, ClientSession.MessageListener.NOOP);
    }

    /**
     * @param listener the listener to register and invoke when a message arrives on this channel
     * @param callback the callback to notify of the subscribe result
     * @deprecated use {@link #subscribe(MessageListener, ClientSession.MessageListener)} instead
     */
    @Deprecated
    public default void subscribe(MessageListener listener, MessageListener callback) {
        subscribe(listener, message -> callback.onMessage(this, message));
    }

    /**
     * <p>Equivalent to {@link #subscribe(Message.Mutable, MessageListener, ClientSession.MessageListener)
     * subscribe(null, listener, callback)}.</p>
     *
     * @param listener the listener to register and invoke when a message arrives on this channel
     * @param callback the callback to notify of the subscribe result
     * @return whether the subscribe message has been sent
     */
    public default boolean subscribe(MessageListener listener, ClientSession.MessageListener callback) {
        return subscribe(null, listener, callback);
    }

    /**
     * <p>Subscribes the given {@code listener} to receive messages sent to this channel.</p>
     * <p>Subscription involves communication with the server only for the first listener
     * subscribed to this channel. Listeners registered after the first will not cause a message
     * being sent to the server.</p>
     * <p>The callback parameter will be invoked upon acknowledgment of the subscription
     * by the server, and therefore only for the first subscription to this channel.</p>
     *
     * @param message  the subscription message
     * @param listener the listener to register and invoke when a message arrives on this channel
     * @param callback the callback to notify of the subscribe result
     * @return whether the subscribe message has been sent
     * @see #unsubscribe(MessageListener)
     * @see #addListener(ClientSessionChannelListener)
     */
    public boolean subscribe(Message.Mutable message, MessageListener listener, ClientSession.MessageListener callback);

    /**
     * <p>Equivalent to {@link #unsubscribe(ClientSessionChannel.MessageListener, ClientSession.MessageListener)
     * unsubscribe(listener, null)}.</p>
     *
     * @param listener the listener to unsubscribe
     * @return whether the unsubscribe message has been sent
     */
    public default boolean unsubscribe(MessageListener listener) {
        return unsubscribe(listener, ClientSession.MessageListener.NOOP);
    }

    /**
     * @param listener the listener to unsubscribe
     * @param callback the callback to notify of the unsubscribe result
     * @deprecated use {@link #unsubscribe(MessageListener, ClientSession.MessageListener)} instead
     */
    @Deprecated
    public default void unsubscribe(MessageListener listener, MessageListener callback) {
        unsubscribe(listener, message -> callback.onMessage(this, message));
    }

    /**
     * <p>Equivalent to {@link #unsubscribe(Message.Mutable, MessageListener, ClientSession.MessageListener)
     * unsubscribe(null, listener, callback)}.</p>
     *
     * @param listener the listener to unsubscribe
     * @param callback the callback to notify of the unsubscribe result
     * @return whether the unsubscribe message has been sent
     */
    public default boolean unsubscribe(MessageListener listener, ClientSession.MessageListener callback) {
        return unsubscribe(null, listener, callback);
    }

    /**
     * <p>Unsubscribes the given {@code listener} from receiving messages sent to this channel.</p>
     * <p>Unsubscription involves communication with the server only for the last listener
     * unsubscribed from this channel.</p>
     * <p>The callback parameter will be invoked upon acknowledgment of the unsubscription
     * by the server, and therefore only for the last unsubscription from this channel.</p>
     *
     * @param message  the unsubscription message
     * @param listener the listener to unsubscribe
     * @param callback the callback to notify of the unsubscribe result
     * @return whether the unsubscribe message has been sent
     * @see #subscribe(MessageListener)
     * @see #unsubscribe()
     */
    public boolean unsubscribe(Message.Mutable message, MessageListener listener, ClientSession.MessageListener callback);

    /**
     * <p>Unsubscribes all subscribers registered on this channel.</p>
     *
     * @see #subscribe(MessageListener)
     */
    public void unsubscribe();

    /**
     * @return an immutable snapshot of the subscribers
     * @see #subscribe(MessageListener)
     */
    public List<MessageListener> getSubscribers();

    /**
     * <p>Releases this channel from its {@link ClientSession}.</p>
     * <p>If the release is successful, subsequent invocations of {@link ClientSession#getChannel(String)}
     * will return a new, different, instance of a ClientSessionChannel.</p>
     * <p>The release of a ClientSessionChannel is successful only if no listeners and no
     * subscribers are present at the moment of the release.</p>
     *
     * @return true if the release was successful, false otherwise
     * @see #isReleased()
     */
    public boolean release();

    /**
     * @return whether this channel has been released
     * @see #release()
     */
    public boolean isReleased();

    /**
     * <p>Represents a listener on a {@link ClientSessionChannel}.</p>
     * <p>Sub-interfaces specify the exact semantic of the listener.</p>
     */
    public interface ClientSessionChannelListener extends Bayeux.BayeuxListener {
    }

    /**
     * A listener for messages on a {@link ClientSessionChannel}.
     */
    public interface MessageListener extends ClientSessionChannelListener {
        /**
         * Callback invoked when a message is received on the given {@code channel}.
         *
         * @param channel the channel that received the message
         * @param message the message received
         */
        public void onMessage(ClientSessionChannel channel, Message message);
    }
}
