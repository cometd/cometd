package org.cometd.bayeux;

import java.util.EventListener;


/* ------------------------------------------------------------ */
/** Bayeux Interface.
 * <p>
 * This interface represents the prime interface for a bayeux implementation
 * for non session related configuration, extensions and listeners.
 * Both client and server interfaces are derived from this common interface.
 */
public interface Bayeux
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this bayeux implementation.
     * @param extension
     */
    void addExtension(Extension extension);

    /* ------------------------------------------------------------ */
    /** Get a channel,
     * @param channelId
     * @return A Channel instance, which may be an {@link org.cometd.bayeux.client.SessionChannel}
     * or a {@link org.cometd.bayeux.server.ServerChannel} depending on the actual actual implementation.
     */
    Channel getChannel(String channelId);

    /* ------------------------------------------------------------ */
    /** Add a listeners
     * @throws IllegalArgumentException if the type of the listener is not supported
     * @param listener A listener derived from {@link BayeuxListener}, that must also be 
     * suitable for the derived {@link Bayeux} implementation.
     */
    void addListener(BayeuxListener listener);
    
    /* ------------------------------------------------------------ */
    /** Remove a listener
     * @param listener The listener to be removed.
     */
    void removeListener(BayeuxListener listener);


    /* ------------------------------------------------------------ */
    /** Create a new Message.
     * <p>
     * The message will be of a type suitable for the Bayeux implementation,
     * so for a Bayeux server, it will be a ServerMessage etc.
     * @return A new or recycled message instance.
     */
    Message.Mutable newMessage();
    
    
    /* ------------------------------------------------------------ */
    /** BayeuxListener.
     * All Bayeux Listeners are derived from this interface.
     */
    interface BayeuxListener extends EventListener
    {};
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Bayeux Extension.
     * <p>
     * A registered extension is called once for every message
     * received and sent by the bayeux server.  Extensions may
     * modify messages and all clients will see the results
     * of those modifications.
     * <p>
     * Bayeux.Extension receive methods are called before 
     * ServerSession.Extension receive methods.  
     * ServerSession.Extension send methods are called 
     * before Bayeux.Extension send methods.
     */
    public interface Extension
    {
        /**
         * @param from
         * @param message
         * @return true if message processing should continue
         */
        boolean rcv(Session from, Message.Mutable message);

        /**
         * @param from
         * @param message
         * @return true if message processing should continue
         */
        boolean rcvMeta(Session from, Message.Mutable message);

        /**
         * @param from
         * @param message
         * @return true if message processing should continue
         */
        boolean send(Session from, Message.Mutable message);

        /**
         * @param from
         * @param message
         * @return true if message processing should continue
         */
        boolean sendMeta(Session from, Message.Mutable message);
    }
}
