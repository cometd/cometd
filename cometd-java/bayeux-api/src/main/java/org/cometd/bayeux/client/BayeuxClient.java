package org.cometd.bayeux.client;


import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;


/**
 * The Bayeux client interface represents  the static state of the
 * client via the {@link Bayeux} interface
 */
public interface BayeuxClient extends Bayeux
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this client.
     * @param extension
     */
    void addExtension(Extension extension);
    
    
    /* ------------------------------------------------------------ */
    /** Create a new session
     * @param servers A list of server URIs to try in turn to connect 
     * to, each in the format "[protocol:]host[:port]/path"
     * @return
     */
    ClientSession newSession(String... servers);

    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * <p>Extension API for client session.</p>
     *
     * @see ClientSession#addExtension(Extension)
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is incoming.
         * @param session the session object
         * @param message the incoming message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is incoming.
         * @param session the session object
         * @param message the incoming meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a normal message is outgoing.
         * @param session the session object
         * @param message the outgoing message
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is outgoing.
         * @param session the session object
         * @param message the outgoing meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ClientSession session, Message.Mutable message);
    }
}
