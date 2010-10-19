package org.cometd.bayeux.server;

import java.util.Set;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.ClientSessionChannel;

/**
 * <p>Server side representation of a Bayeux channel.</p>
 * <p>{@link ServerChannel} is the entity that holds a set of
 * {@link ServerSession}s that are subscribed to the channel itself.</p>
 * <p>A message published to a {@link ServerChannel} will be delivered to
 * all the {@link ServerSession}'s subscribed to the channel.</p>
 * <p>Contrary to their client side counterpart ({@link ClientSessionChannel})
 * a {@link ServerChannel} is not scoped with a session.</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface ServerChannel extends ConfigurableServerChannel
{
    /**
     * @return a snapshot of the set of subscribers of this channel
     */
    Set<ServerSession> getSubscribers();

    /**
     * A broadcasting channel is a channel that is neither a meta channel
     * not a service channel.
     * @return whether the channel is a broadcasting channel
     */
    boolean isBroadcast();

    /**
     * <p>Publishes the given message to this channel, delivering
     * the message to all the {@link ServerSession}s subscribed to
     * this channel.</p>
     *
     * @param from the session from which the message originates
     * @param message the message to publish
     * @see #publish(Session, Object, String)
     */
    void publish(Session from, ServerMessage.Mutable message);

    /**
     * <p>Publishes the given information to this channel.</p>
     * @param from the session from which the message originates
     * @param data the data of the message
     * @param id the id of the message
     * @see #publish(Session, ServerMessage)
     */
    void publish(Session from, Object data, String id);

    /**
     * <p>Removes this channel, and all the children channels.</p>
     * <p>If channel "/foo", "/foo/bar" and "/foo/blip" exist,
     * removing channel "/foo" will remove also "/foo/bar" and
     * "/foo/blip".</p>
     * <p>The removal will notify {@link BayeuxServer.ChannelListener}
     * listeners.
     */
    void remove();

    /**
     * <p>Common interface for {@link ServerChannel} listeners.</p>
     * <p>Specific sub-interfaces define what kind of event listeners will be notified.</p>
     */
    interface ServerChannelListener extends Bayeux.BayeuxListener
    {
    }

    /**
     * <p>Listeners objects that implement this interface will be notified of message publish.</p>
     */
    public interface MessageListener extends ServerChannelListener
    {
        /**
         * <p>Callback invoked when a message is being published.</p>
         * <p>Implementors can decide to return false to signal that the message should not be
         * published.</p>
         * @param from the session that publishes the message
         * @param channel the channel the message is published to
         * @param message the message to be published
         * @return whether the message should be published or not
         */
        boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message);
    }

    /**
     * <p>Listeners object that implement this interface will be notified of subscription events.</p>
     */
    public interface SubscriptionListener extends ServerChannelListener
    {
        /**
         * Callback invoked when the given {@link ServerSession} subscribes to the given {@link ServerChannel}.
         * @param session the session that subscribes
         * @param channel the channel the session subscribes to
         */
        public void subscribed(ServerSession session, ServerChannel channel);

        /**
         * Callback invoked when the given {@link ServerSession} unsubscribes from the given {@link ServerChannel}.
         * @param session the session that unsubscribes
         * @param channel the channel the session unsubscribes from
         */
        public void unsubscribed(ServerSession session, ServerChannel channel);
    }
}
