package org.cometd.bayeux.client;


import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Message;


/**
 * The Bayeux client interface represents  the static state of the
 * client via the {@link Bayeux} interface
 */
public interface BayeuxClient 
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this client.
     * @param extension
     */
    void addExtension(Extension extension);
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param channel
     * @return
     */
    ClientChannel getChannel(String channel);
    
    /* ------------------------------------------------------------ */
    /** Create a new session
     * @param servers A list of servers to try in turn to connect 
     * to, each in the format "host[:port]/path"
     * @return
     */
    ClientSession newSession(String... servers);

    /* ------------------------------------------------------------ */
    /** 
     * @return The set of know transport names
     */
    Set<String> getKnownTransports();
    
    
    /* ------------------------------------------------------------ */
    /** Get transport options
     * @param transport transport name or "*" for common transport options
     * @return Mutable Map of transport options or null if unknown transport
     */
    Map<String,Object> getTransportOptions(String transport);
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return List of transports to be used for sessions in the order 
     * they will be tried.
     */
    List<String> getAllowedTransports();
    
    /* ------------------------------------------------------------ */
    /**
     * @param transports List of transports to be used for sessions in the order 
     * they will be tried.
     */
    void setAllowedTransports(String... transports);
    

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
