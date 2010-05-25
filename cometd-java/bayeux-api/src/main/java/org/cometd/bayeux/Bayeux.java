package org.cometd.bayeux;

import java.util.List;
import java.util.Set;

/**
 * <p>The {@link Bayeux} interface is the common API for both client-side and
 * server-side configuration and usage of the Bayeux object.</p>
 * <p>The {@link Bayeux} object handles configuration options and a set of
 * transports that is negotiated with the server.</p>
 * @see Transport
 */
public interface Bayeux
{
    /**
     * @return the set of known transport names of this {@link Bayeux} object.
     */
    Set<String> getKnownTransportNames();

    /**
     * @param transport the transport name
     * @return the transport with the given name or null
     * if no such transport exist
     */
    Transport getTransport(String transport);

    /**
     * @return the ordered list of transport names that will be used in the
     * negotiation of transports with the other peer.
     */
    List<String> getAllowedTransports();

    /**
     * @param qualifiedName the configuration option name
     * @return the configuration option with the given {@code qualifiedName}
     * @see #setOption(String, Object)
     * @see #getOptionNames()
     */
    Object getOption(String qualifiedName);

    /**
     * @param qualifiedName the configuration option name
     * @param value the configuration option value
     * @see #getOption(String)
     */
    void setOption(String qualifiedName, Object value);

    /**
     * @return the set of configuration options
     */
    Set<String> getOptionNames();
}
