package org.cometd.bayeux;

import java.util.Set;

/**
 * <p>A transport abstract the details of the protocol used to send
 * Bayeux messages over the network, for example using HTTP or using
 * WebSocket.</p>
 * <p>{@link Transport}s have well known names and both a Bayeux client
 * and a Bayeux server can negotiate the transport they want to use by
 * exchanging the list of supported transport names.</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface Transport
{
    /**
     * @return The well known name of this transport, used in transport negotiations
     * @see Bayeux#getAllowedTransports()
     */
    String getName();

    /**
     * @param name the configuration option name
     * @return the configuration option with the given {@code qualifiedName}
     * @see #getOptionNames()
     */
    Object getOption(String name);

    /**
     * @return the set of configuration options
     * @see #getOption(String)
     */
    Set<String> getOptionNames();

    // TODO: remove ?
    String getOptionPrefix();
}
