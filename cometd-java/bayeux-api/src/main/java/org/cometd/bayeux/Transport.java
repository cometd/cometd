package org.cometd.bayeux;

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
    Object getOption(String name);
    
    /* ------------------------------------------------------------ */
    Set<String> getOptionNames();

    /* ------------------------------------------------------------ */
    String getOptionPrefix();
    
}
