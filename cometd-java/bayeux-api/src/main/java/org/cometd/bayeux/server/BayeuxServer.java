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
package org.cometd.bayeux.server;

import java.util.List;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;

/**
 * <p>The server-side Bayeux interface.</p>
 * <p>An instance of the {@link BayeuxServer} interface is available to
 * web applications via the "{@value #ATTRIBUTE}" attribute
 * of the {@code javax.servlet.ServletContext}.</p>
 * <p>The {@link BayeuxServer} APIs give access to the
 * {@link ServerSession}s via the {@link #getSession(String)}
 * method.  It also allows new {@link LocalSession} to be
 * created within the server using the {@link #newLocalSession(String)}
 * method.</p>
 * <p>{@link ServerChannel} instances may be accessed via the
 * {@link #getChannel(String)} method, but the server has
 * no direct relationship with {@link ClientSessionChannel}s or
 * {@link ClientSession}.</p>
 * <p>If subscription semantics is required, then
 * the {@link #newLocalSession(String)} method should be used to
 * create a {@link LocalSession} that can subscribe and publish
 * like a client-side Bayeux session.</p>
 */
public interface BayeuxServer extends Bayeux {
    /**
     * ServletContext attribute name used to obtain the Bayeux object
     */
    public static final String ATTRIBUTE = "org.cometd.bayeux";

    /**
     * <p>Adds the given extension to this Bayeux object.</p>
     *
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * <p>Removes the given extension from this Bayeux object.</p>
     *
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * @return an immutable list of extensions present in this BayeuxServer instance
     * @see #addExtension(Extension)
     */
    List<Extension> getExtensions();

    /**
     * <p>Adds a listener to this Bayeux object.</p>
     *
     * @param listener the listener to add
     * @see #removeListener(BayeuxServerListener)
     */
    void addListener(BayeuxServerListener listener);

    /**
     * <p>Removes a listener from this Bayeux object.</p>
     *
     * @param listener the listener to remove
     * @see #addListener(BayeuxServerListener)
     */
    void removeListener(BayeuxServerListener listener);

    /**
     * @param channelId the channel identifier
     * @return a {@link ServerChannel} with the given {@code channelId},
     * or null if no such channel exists
     * @see #createChannelIfAbsent(String, ConfigurableServerChannel.Initializer...)
     */
    ServerChannel getChannel(String channelId);

    /**
     * @return the list of channels known to this BayeuxServer object
     */
    List<ServerChannel> getChannels();

    /**
     * <p>Creates a {@link ServerChannel} and initializes it atomically if the
     * channel does not exist, or returns it if it already exists.</p>
     * <p>This method can be used instead of adding a {@link ChannelListener}
     * to atomically initialize a channel. The {@code initializers} will be
     * called before any other thread can access the new channel instance.</p>
     * <p>This method should be used when a channel needs to be
     * initialized (e.g. by adding listeners) before any publish or subscribes
     * can occur on the channel, or before any other thread may concurrently
     * create the same channel.</p>
     *
     * @param channelName  the channel name
     * @param initializers the initializers invoked to configure the channel
     * @return a {@link MarkedReference} whose reference is the channel, and
     * the mark signals whether the channel has been created because it
     * did not exist before.
     */
    MarkedReference<ServerChannel> createChannelIfAbsent(String channelName, ConfigurableServerChannel.Initializer... initializers);

    /**
     * @param clientId the {@link ServerSession} identifier
     * @return the {@link ServerSession} with the given {@code clientId}
     * or null if no such valid session exists.
     */
    ServerSession getSession(String clientId);

    /**
     * @return the list of {@link ServerSession}s known to this BayeuxServer object
     */
    List<ServerSession> getSessions();

    /**
     * <p>Removes the given {@code session} from this BayeuxServer.</p>
     * <p>This method triggers the invocation of all listeners that would be called
     * if the session was disconnected or if the session timed out.</p>
     *
     * @param session the session to remove
     * @return true if the session was known to this BayeuxServer and was removed
     */
    boolean removeSession(ServerSession session);

    /**
     * <p>Creates a new {@link LocalSession}.</p>
     * <p>A {@link LocalSession} is a server-side ClientSession that allows
     * server-side code to have special clients (resident within the same JVM)
     * that can be used to publish and subscribe like a client-side session
     * would do.</p>
     *
     * @param idHint a hint to be included in the unique clientId of the session.
     * @return a new {@link LocalSession}
     */
    LocalSession newLocalSession(String idHint);

    /**
     * @return a new or recycled mutable message instance.
     */
    ServerMessage.Mutable newMessage();

    /**
     * @return the {@link SecurityPolicy} associated with this session
     * @see #setSecurityPolicy(SecurityPolicy)
     */
    public SecurityPolicy getSecurityPolicy();

    /**
     * @param securityPolicy the {@link SecurityPolicy} associated with this session
     * @see #getSecurityPolicy()
     */
    public void setSecurityPolicy(SecurityPolicy securityPolicy);

    /**
     * <p>Common base interface for all server-side Bayeux listeners.</p>
     */
    interface BayeuxServerListener extends BayeuxListener {
    }

    /**
     * <p>Specialized listener for {@link ServerChannel} events.</p>
     * <p>The {@link ServerChannel.Initializer#configureChannel(ConfigurableServerChannel)}
     * method is called atomically during Channel creation so that the channel may be configured
     * before use. It is guaranteed that in case of concurrent channel creation, the
     * {@link ServerChannel.Initializer#configureChannel(ConfigurableServerChannel)} is
     * invoked exactly once.</p>
     * <p>The other methods are called asynchronously when a channel is added to or removed
     * from a {@link BayeuxServer}, and there is no guarantee that these methods will be called
     * before any other {@link ServerChannel.ServerChannelListener server channel listeners}
     * that may be added during channel configuration.</p>
     */
    public interface ChannelListener extends BayeuxServerListener, ConfigurableServerChannel.Initializer {
        /**
         * <p>Callback invoked when a {@link ServerChannel} has been added to a {@link BayeuxServer} object.</p>
         *
         * @param channel the channel that has been added
         */
        public void channelAdded(ServerChannel channel);

        /**
         * <p>Callback invoked when a {@link ServerChannel} has been removed from a {@link BayeuxServer} object.</p>
         *
         * @param channelId the channel identifier of the channel that has been removed.
         */
        public void channelRemoved(String channelId);
    }

    /**
     * <p>Specialized listener for {@link ServerSession} events.</p>
     * <p>This listener is called when a {@link ServerSession} is added
     * or removed from a {@link BayeuxServer}.</p>
     */
    public interface SessionListener extends BayeuxServerListener {
        /**
         * <p>Callback invoked when a {@link ServerSession} has been added to a {@link BayeuxServer} object.</p>
         *
         * @param session the session that has been added
         * @param message the handshake message from the client
         */
        public void sessionAdded(ServerSession session, ServerMessage message);

        /**
         * <p>Callback invoked when a {@link ServerSession} has been removed from a {@link BayeuxServer} object.</p>
         *
         * @param session  the session that has been removed
         * @param timedout whether the session has been removed for a timeout or not
         */
        public void sessionRemoved(ServerSession session, boolean timedout);
    }

    /**
     * <p>Specialized listener for {@link ServerChannel} subscription events.</p>
     * <p>This listener is called when a subscribe message or an unsubscribe message
     * occurs for any channel known to the {@link BayeuxServer}.</p>
     * <p>This interface the correspondent of the {@link ServerChannel.SubscriptionListener}
     * interface, but it is invoked for any session and any channel known to the
     * {@link BayeuxServer}, <em>after</em> having invoked the {@link ServerChannel.SubscriptionListener}.</p>
     */
    public interface SubscriptionListener extends BayeuxServerListener {
        /**
         * <p>Callback invoked when a {@link ServerSession} subscribes to a {@link ServerChannel}.</p>
         *
         * @param session the session that subscribes
         * @param channel the channel to subscribe to
         * @param message the subscription message sent by the client, or null in case of
         *                server-side subscription via {@link ServerChannel#subscribe(ServerSession)}
         */
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message);

        /**
         * <p>Callback invoked when a {@link ServerSession} unsubscribes from a {@link ServerChannel}.</p>
         *
         * @param session the session that unsubscribes
         * @param channel the channel to unsubscribe from
         * @param message the unsubscription message sent by the client, or null in case of
         *                server-side unsubscription via {@link ServerChannel#unsubscribe(ServerSession)}
         */
        public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message);
    }

    /**
     * <p>Extension API for {@link BayeuxServer}.</p>
     * <p>Implementations of this interface allow to modify incoming and outgoing messages
     * before any other processing performed by the implementation.</p>
     * <p>Multiple extensions can be registered; the extension <em>receive</em> methods
     * are invoked in registration order, while the extension <em>send</em> methods are
     * invoked in registration reverse order.</p>
     *
     * @see BayeuxServer#addExtension(Extension)
     */
    public interface Extension {
        /**
         * <p>Callback method invoked every time a message is incoming.</p>
         *
         * @param from    the session that sent the message
         * @param message the incoming message
         * @param promise the promise to notify whether message processing should continue
         */
        default void incoming(ServerSession from, ServerMessage.Mutable message, Promise<Boolean> promise) {
            promise.succeed(message.isMeta() ? rcvMeta(from, message) : rcv(from, message));
        }

        /**
         * <p>Blocking version of {@link #incoming(ServerSession, ServerMessage.Mutable, Promise)}
         * for non-meta messages.</p>
         *
         * @param from    the session that sent the message
         * @param message the incoming message
         * @return whether message processing should continue
         */
        default boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            return true;
        }

        /**
         * <p>Blocking version of {@link #incoming(ServerSession, ServerMessage.Mutable, Promise)}
         * for meta messages.</p>
         *
         * @param from    the session that sent the message
         * @param message the incoming message
         * @return whether message processing should continue
         */
        default boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return true;
        }

        /**
         * <p>Callback method invoked every time a message is outgoing.</p>
         *
         * @param from    the session that sent the message or null
         * @param to      the session the message is sent to, or null for a publish.
         * @param message the outgoing message
         * @param promise the promise to notify whether message processing should continue
         */
        default void outgoing(ServerSession from, ServerSession to, ServerMessage.Mutable message, Promise<Boolean> promise) {
            promise.succeed(message.isMeta() ? sendMeta(to, message) : send(from, to, message));
        }

        /**
         * <p>Blocking version of {@link #outgoing(ServerSession, ServerSession, ServerMessage.Mutable, Promise)}
         * for non-meta messages.</p>
         *
         * @param from    the session that sent the message or null
         * @param to      the session the message is sent to, or null for a publish.
         * @param message the outgoing message
         * @return whether message processing should continue
         */
        default boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            return true;
        }

        /**
         * <p>Blocking version of {@link #outgoing(ServerSession, ServerSession, ServerMessage.Mutable, Promise)}
         * for meta messages.</p>
         *
         * @param to      the session the message is sent to, or null for a publish.
         * @param message the outgoing message
         * @return whether message processing should continue
         */
        default boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
            return true;
        }

        /**
         * Empty implementation of {@link Extension}.
         *
         * @deprecated Use {@link Extension} instead
         */
        @Deprecated
        public static class Adapter implements Extension {
        }
    }
}
