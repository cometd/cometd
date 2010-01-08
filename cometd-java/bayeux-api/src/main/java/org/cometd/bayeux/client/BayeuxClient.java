package org.cometd.bayeux.client;


import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Bayeux;


/**
 * The Bayeux client interface represents  the static state of the
 * client via the {@link Bayeux} interface
 */
public interface BayeuxClient extends Bayeux
{
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
    
    
}
