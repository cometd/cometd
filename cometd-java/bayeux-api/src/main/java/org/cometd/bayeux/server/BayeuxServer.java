package org.cometd.bayeux.server;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;

/* ------------------------------------------------------------ */
/**
 * <p>The server-side Bayeux interface.</p>
 * <p>An instance of the {@link BayeuxServer} interface is available to
 * web applications via the "{@value #ATTRIBUTE}" attribute
 * of the {@link javax.servlet.ServletContext servlet context}.</p>
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
public interface BayeuxServer extends Bayeux
{
    /** ServletContext attribute name used to obtain the Bayeux object */
    public static final String ATTRIBUTE ="org.cometd.bayeux";

    /**
     * Adds the given extension to this Bayeux object.
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * Removes the given extension from this Bayeux object
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * Adds a listener to this Bayeux object.
     * @param listener the listener to add
     * @see #removeListener(BayeuxServerListener)
     */
    void addListener(BayeuxServerListener listener);

    /**
     * Removes a listener from this Bayeux object.
     * @param listener the listener to remove
     * @see #addListener(BayeuxServerListener)
     */
    void removeListener(BayeuxServerListener listener);

    /**
     * @param channelId the channel identifier
     * @return a {@link ServerChannel} with the given {@code channelId},
     * or null if no such channel exists
     */
    ServerChannel getChannel(String channelId);

    /**
     * @param channelId the channel identifier
     * @param create whether to create the channel if it does not exist
     * @return a {@link ServerChannel} with the given {@code channelId}
     * @deprecated Use {@link #createIfAbsent(String, ConfigurableServerChannel.Initializer...)} and
     * {@link #getChannel(String)} instead
     */
    @Deprecated
    ServerChannel getChannel(String channelId, boolean create);

    /**
     * <p>Creates a {@link ServerChannel} and initializes it atomically.</p>
     * <p>This method can be used instead of adding a {@link ChannelListener}
     * to atomically initialize a channel. The initializer will be called before
     * any other thread can access the new channel instance.</p>
     * <p>The createIfAbsent method should be used when a channel needs to be
     * intialized (e.g. by adding listeners) before any publish or subscribes
     * can occur on the channel, or before any other thread may concurrently
     * create the same channel.
     *
     * @param channelId the channel identifier
     * @param initializers the initializers invoked to configure the channel
     * @return true if the channel was initialized, false otherwise
     */
    boolean createIfAbsent(String channelId, ServerChannel.Initializer... initializers);

    /**
     * @param clientId the {@link ServerSession} identifier
     * @return the {@link ServerSession} with the given {@code clientId}
     * or null if no such valid session exists.
     */
    ServerSession getSession(String clientId);

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
     * @return the current transport instance of the current thread
     */
    public Transport getCurrentTransport();

    /**
     * Common base interface for all server-side Bayeux listeners
     */
    interface BayeuxServerListener extends BayeuxListener
    {
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
    public interface ChannelListener extends BayeuxServerListener, ServerChannel.Initializer
    {
        /**
         * Callback invoked when a {@link ServerChannel} has been added to a {@link BayeuxServer} object.
         * @param channel the channel that has been added
         */
        public void channelAdded(ServerChannel channel);

        /**
         * Callback invoked when a {@link ServerChannel} has been removed from a {@link BayeuxServer} object.
         * @param channelId the channel identifier of the channel that has been removed.
         */
        public void channelRemoved(String channelId);
    }

    /**
     * <p>Specialized listener for {@link ServerSession} events.</p>
     * <p>This listener is called when a {@link ServerSession} is added
     * or removed from a {@link BayeuxServer}.</p>
     */
    public interface SessionListener extends BayeuxServerListener
    {
        /**
         * Callback invoked when a {@link ServerSession} has been added to a {@link BayeuxServer} object.
         * @param session the session that has been added
         */
        public void sessionAdded(ServerSession session);

        /**
         * Callback invoked when a {@link ServerSession} has been removed from a {@link BayeuxServer} object.
         * @param session the session that has been removed
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
     * {@link BayeuxServer}.</p>
     */
    public interface SubscriptionListener extends BayeuxServerListener
    {
        /**
         * Callback invoked when a {@link ServerSession} subscribes to a {@link ServerChannel}.
         * @param session the session that subscribes
         * @param channel the channel to subscribe to
         */
        public void subscribed(ServerSession session, ServerChannel channel);

        /**
         * Callback invoked when a {@link ServerSession} unsubscribes from a {@link ServerChannel}.
         * @param session the session that unsubscribes
         * @param channel the channel to unsubscribe from
         */
        public void unsubscribed(ServerSession session, ServerChannel channel);
    }

    /**
     * <p>Extension API for {@link BayeuxServer}.</p>
     * <p>Implementations of this interface allow to modify incoming and outgoing messages
     * respectively just before and just after they are handled by the implementation,
     * either on client side or server side.</p>
     * <p>Extensions are be registered in order and one extension may allow subsequent
     * extensions to process the message by returning true from the callback method, or
     * forbid further processing by returning false.</p>
     *
     * @see BayeuxServer#addExtension(Extension)
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is incoming.
         * @param from the session that sent the message
         * @param message the incoming message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ServerSession from, ServerMessage.Mutable message);

        /**
         * Callback method invoked every time a meta message is incoming.
         * @param from the session that sent the message
         * @param message the incoming meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ServerSession from, ServerMessage.Mutable message);

        /**
         * Callback method invoked every time a normal message is outgoing.
         * @param to the session receiving the message, or null for a publish
         * @param message the outgoing message
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(ServerMessage.Mutable message);

        /**
         * Callback method invoked every time a meta message is outgoing.
         * @param to the session receiving the message
         * @param message the outgoing meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ServerSession to, ServerMessage.Mutable message);
    }
}
