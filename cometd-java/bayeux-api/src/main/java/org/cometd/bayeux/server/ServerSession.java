package org.cometd.bayeux.server;


import java.util.Queue;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.SessionChannel;


/**
 * Bayeux Server Session.
 * <p>
 * This interface represents the server-side of a bayeux session.
 * The server side of a bayeux session contains the queue of messages
 * to be delivered to the client side of the session.  Messages are
 * normally queued on a server session by being published to a
 * channel to which the session is subscribed, however the {@link #deliver(Session, ServerMessage)}
 * and {@link #deliver(Session, String, Object, Object)} methods may
 * be used to directly queue messages to a session without
 * publishing them to all subscribers for a channel.
 *
 *
 */
public interface ServerSession extends Session
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this session.
     * @param extension
     */
    void addExtension(Extension extension);

    void removeExtension(Extension extension);

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addListener(ServerSessionListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void removeListener(ServerSessionListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @return True if this is a session for a local server-side client
     */
    boolean isLocalSession();

    /* ------------------------------------------------------------ */
    /** Get the local session.
     * @return The LocalSession or null if this is a session for a
     * remote client.
     */
    LocalSession getLocalSession();

    /* ------------------------------------------------------------ */
    /**
     * Deliver the message to the session listeners and queue.
     * <p>
     * This is different to a {@link SessionChannel#publish(Object)}
     * call, as the message is delivered only to this client and
     * not to all subscribers to the channel.  The message should still
     * have a channel id specified, so that the ClientSession may
     * identify which handler the message should be delivered to.
     * @param from
     * @param msg
     */
    void deliver(Session from, ServerMessage msg);

    /* ------------------------------------------------------------ */
    /**
     * Deliver the message to the session listeners and queue.
     * <p>
     * This is different to a {@link SessionChannel#publish(Object)}
     * call, as the message is delivered only to this client and
     * not to all subscribers to the channel.  The message should still
     * have a channel id specified, so that the ClientSession may
     * identify which handler the message should be delivered to.
     */
    void deliver(Session from, String channel, Object data, String id);

    /* ------------------------------------------------------------ */
    /**
     * Disconnect this session.
     */
    void disconnect();


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    interface ServerSessionListener extends BayeuxListener
    {}

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Queue a message listener
     * <p>
     * Listener called after a session is removed
     */
    public interface RemoveListener extends ServerSessionListener
    {
        public void removed(ServerSession session, boolean timeout);
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Queue a message listener
     * <p>
     * Listener called before each message is queued.
     */
    public interface MessageListener extends ServerSessionListener
    {
        /**
         * Listener called before each message is queued.
         */
        public boolean onMessage(ServerSession to, ServerSession from, ServerMessage message);
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface DeQueueListener extends ServerSessionListener
    {
        /* ------------------------------------------------------------ */
        /**
         * callback to notify that the queue is about to be sent to the
         * client.  This is the last chance to process the queue and remove
         * duplicates or merge messages.
         */
        public void deQueue(ServerSession session,Queue<ServerMessage> queue);
    };


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface MaxQueueListener extends ServerSessionListener
    {
        /* ------------------------------------------------------------ */
        /**
         * Call back to notify if a message for a client will result in the
         * message queue exceeding {@link Session#getMaxQueue()}.
         * This is called with the client instance locked, so it is safe for the
         * handler to manipulate the queue returned by {@link Session#getQueue()}, but
         * action in the callback that may result in another Client instance should be
         * avoided as that would risk deadlock.
         * @param session Client message is being delivered to
         * @param from Client message is published from
         * @param message
         * @return true if the message should be added to the client queue
         */
        public boolean queueMaxed(ServerSession session, Session from, Message message);
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
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
