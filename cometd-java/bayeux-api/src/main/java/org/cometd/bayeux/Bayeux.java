package org.cometd.bayeux;

import java.util.List;
import java.util.Set;

public interface Bayeux
{
    /* ------------------------------------------------------------ */
    /** 
     * @return The set of know transport names
     */
    Set<String> getKnownTransportNames();
    
    
    /* ------------------------------------------------------------ */
    /** Get a transport
     * @param transport transport name or "*" for a Transport representing common options.
     * @return Mutable Map of transport options or null if unknown transport
     */
    Transport getTransport(String transport);
    
    /* ------------------------------------------------------------ */
    /**
     * @return List of transports to be used for sessions in the order 
     * they will be tried.
     */
    List<String> getAllowedTransports();
    
    /* ------------------------------------------------------------ */
    /**
     * @param transports List of transports to be used for sessions in the order 
     * they will be tried.  Unknown transports in this list are silently ignored.
     */
    void setAllowedTransports(String... transports);
}
