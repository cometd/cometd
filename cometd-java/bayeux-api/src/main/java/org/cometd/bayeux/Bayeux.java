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
    /** BayeuxListener.
     * All Bayeux Listeners are derived from this interface.
     */
    interface BayeuxListener extends EventListener
    {};
}
