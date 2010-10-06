package org.cometd.bayeux.server;

import java.util.Queue;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.ServerMessage.Mutable;


/**
 * <p>Objects implementing this interface are the server-side representation of remote Bayeux clients.</p>
 * <p>{@link ServerSession} contains the queue of messages to be delivered to the client; messages are
 * normally queued on a {@link ServerSession} by publishing them to a channel to which the session is
 * subscribed (via {@link ServerChannel#publish(Session, ServerMessage)}).</p>
 * <p>The {@link #deliver(Session, Mutable)} and {@link #deliver(Session, String, Object, String)}
 * methods may be used to directly queue messages to a session without publishing them to all subscribers
 * of a channel.</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface ServerSession extends Session
{
    /**
     * Adds the given extension to this session.
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * Removes the given extension from this session
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * Adds the given listener to this session.
     * @param listener the listener to add
     * @see #removeListener(ServerSessionListener)
     */
    void addListener(ServerSessionListener listener);

    /**
     * Removes the given listener from this session.
     * @param listener the listener to remove
     * @see #addListener(ServerSessionListener)
     */
    void removeListener(ServerSessionListener listener);

    /**
     * @return whether this is a session for a local client on server-side
     */
    boolean isLocalSession();

    /**
     * @return the {@link LocalSession} associated with this session,
     * or null if this is a session representing a remote client.
     */
    LocalSession getLocalSession();

    /**
     * <p>Delivers the given message to this session.</p>
     * <p>This is different from {@link ServerChannel#publish(Session, ServerMessage)}
     * as the message is delivered only to this session and
     * not to all subscribers of the channel.</p>
     * <p>The message should still have a channel id specified, so that the ClientSession
     * may identify the listeners the message should be delivered to.
     * @param from the session delivering the message
     * @param message the message to deliver
     * @see #deliver(Session, String, Object, String)
     */
    void deliver(Session from, ServerMessage.Mutable message);

    /**
     * <p>Delivers the given information to this session.</p>
     * @param from the session delivering the message
     * @param channel the channel of the message
     * @param data the data of the message
     * @param id the id of the message, or null to let the implementation choose an id
     * @see #deliver(Session, Mutable)
     */
    void deliver(Session from, String channel, Object data, String id);

    
    /* ------------------------------------------------------------ */
    /**
     * <p>Get the clients user agent</p>
     * @return The string indicating the client user agent, or null if not known
     */
    String getUserAgent();
    
    
    /**
     * <p>Common interface for {@link ServerSession} listeners.</p>
     * <p>Specific sub-interfaces define what kind of event listeners will be notified.</p>
     */
    interface ServerSessionListener extends Bayeux.BayeuxListener
    {
    }

    /**
     * <p>Listeners objects that implement this interface will be notified of session removal.</p>
     */
    public interface RemoveListener extends ServerSessionListener
    {
        /**
         * Callback invoked when the session is removed.
         * @param session the removed session
         * @param timeout whether the session has been removed because of a timeout
         */
        public void removed(ServerSession session, boolean timeout);
    }

    /**
     * <p>Listeners objects that implement this interface will be notified of message arrival.</p>
     */
    public interface MessageListener extends ServerSessionListener
    {
        /**
         * <p>Callback invoked when a message is received.</p>
         * <p>Implementors can decide to return false to signal that the message should not be
         * processed, meaning that other listeners will not be notified and that the message
         * will be discarded.</p>
         * @param to the session that received the message
         * @param from the session that sent the message
         * @param message the message sent
         * @return whether the processing of the message should continue
         */
        public boolean onMessage(ServerSession to, ServerSession from, ServerMessage message);
    }

    /**
     * <p>Listeners objects that implement this interface will be notified when the session queue
     * is being drained to actually deliver the messages.</p>
     */
    public interface DeQueueListener extends ServerSessionListener
    {
        /**
         * <p>Callback invoked to notify that the queue of messages is about to be sent to the
         * remote client.</p>
         * <p>This is the last chance to process the queue and remove duplicates or merge messages.</p>
         * @param session the session whose messages are being sent
         * @param queue the queue of messages to send
         */
        public void deQueue(ServerSession session,Queue<ServerMessage> queue);
    }

    /**
     * <p>Listeners objects that implement this interface will be notified when the session queue is full.</p>
     */
    public interface MaxQueueListener extends ServerSessionListener
    {
        /**
         * <p>Callback invoked to notify when the message queue is exceeding the value
         * configured for the transport with the option "maxQueue".</p>
         * @param session the session that will receive the message
         * @param from the session that is sending the messages
         * @param message the message that exceeded the max queue capacity
         * @return true if the message should be added to the session queue
         */
        public boolean queueMaxed(ServerSession session, Session from, Message message);
    }

    /**
     * <p>Extension API for {@link ServerSession}.</p>
     * <p>Implementations of this interface allow to modify incoming and outgoing messages
     * respectively just before and just after they are handled by the implementation,
     * either on client side or server side.</p>
     * <p>Extensions are be registered in order and one extension may allow subsequent
     * extensions to process the message by returning true from the callback method, or
     * forbid further processing by returning false.</p>
     *
     * @see ServerSession#addExtension(Extension)
     * @see BayeuxServer.Extension
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is incoming.
         * @param session the session that sent the message
         * @param message the incoming message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ServerSession session, ServerMessage.Mutable message);

        /**
         * Callback method invoked every time a meta message is incoming.
         * @param session the session that is sent the message
         * @param message the incoming meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ServerSession session, ServerMessage.Mutable message);

        /**
         * Callback method invoked every time a normal message is outgoing.
         * @param to the session receiving the message, or null for a publish
         * @param message the outgoing message
         * @return The message to send or null to not send the message
         */
        ServerMessage send(ServerSession to, ServerMessage message);

        /**
         * Callback method invoked every time a meta message is outgoing.
         * @param session the session receiving the message
         * @param message the outgoing meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ServerSession session, ServerMessage.Mutable message);
    }
}
