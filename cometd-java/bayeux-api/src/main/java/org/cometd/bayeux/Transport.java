package org.cometd.bayeux;

import java.util.Map;
import java.util.Set;

public interface Transport
{
    /* ------------------------------------------------------------ */
    /**
     * @return The well known name for the transport, used in handshake
     * negotiations and with the {@link Bayeux#setAllowedTransports(String...)} method.
     */
    String getName();
    
    /* ------------------------------------------------------------ */
    /**
     * @return Map of transport options
     */
    Map<String,Object> getOptions();
    
    /* ------------------------------------------------------------ */
    /**
     * @return the set of options that may be changed after the construction 
     * of the transport. 
     */
    Set<String> getMutableOptions();
}
