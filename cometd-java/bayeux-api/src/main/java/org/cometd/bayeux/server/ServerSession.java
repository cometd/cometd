package org.cometd.bayeux.server;


import java.util.Queue;
import java.util.Set;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.ServerChannel.ServerChannelListener;


/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface ServerSession extends Session
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this session.
     * @param extension
     */
    void addExtension(Extension extension);

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
    /** Get the session message queue.
     * @return The queue of messages awaiting delivery to the client.
     */
    Queue<ServerMessage> getQueue();    

    /* ------------------------------------------------------------ */
    /**
     * Deliver the message to the session listeners and queue.
     * @param from
     * @param msg
     */
    void deliver(ServerSession from, ServerMessage msg);

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
     * Listener called before each message is queued.
     */
    public interface MessageListener extends ServerSessionListener
    {
        public boolean onMessage(ServerSession session, Session from, ServerMessage message);
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
        public void deQueue(ServerSession session);
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
    /** Server Session Extension.
     * <p>
     * A registered extension is called once for every message
     * received and sent by the server session.  Extensions may
     * modify messages except for non-meta sends (which are shared
     * by multiple sessions). 
     * <p>
     * Bayeux.Extension receive methods are called before 
     * ServerSession.Extension receive methods.  
     * ServerSession.Extension send methods are called 
     * before Bayeux.Extension send methods.
     */
    public interface Extension
    {
        /**
         * @param session
         * @param message
         * @return true if message processing should continue
         */
        boolean rcv(ServerSession session, ServerMessage.Mutable message);

        /**
         * @param session
         * @param message
         * @return true if message processing should continue
         */
        boolean rcvMeta(ServerSession session, ServerMessage.Mutable message);

        /**
         * @param session
         * @param message
         * @return The message to send for this session (may be a new message or null to discard).
         */
        ServerMessage send(ServerSession session, ServerSession to, ServerMessage message);

        /**
         * @param session
         * @param message
         * @return true if message processing should continue
         */
        boolean sendMeta(Session session, ServerMessage.Mutable message);
    }
}
