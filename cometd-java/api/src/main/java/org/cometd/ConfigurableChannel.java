package org.cometd;

import java.util.Collection;

/**
 * <p>Represents a the configurable API of a channel.</p>
 * <p>Channels can be configured by registering a listener that implements
 * {@link Initializer} on the Bayeux object. </p>
 * <p>Method {@link Initializer#configureChannel(ConfigurableChannel)} will be
 * called to allow user code to configure the channel, and it is guaranteed
 * that the creation and configuration of a channel is atomic: other threads
 * that may want to create the same channel concurrently will wait until the
 * current thread has completed the creation and configuration of the channel.</p>
 *
 * @version $Revision$ $Date$
 */
public interface ConfigurableChannel
{
    /**
     * @return the channel's name
     */
    String getId();

    /**
     * Indicates whether the channel is persistent or not.
     * Non persistent channels are removed when the last subscription is
     * removed.
     * @return true if the Channel will persist even when all subscriptions are gone.
     * @see #setPersistent(boolean)
     */
    boolean isPersistent();

    /**
     * Sets the persistency of this channel.
     * @param persistent true if the channel is persistent, false otherwise
     * @see #isPersistent()
     */
    void setPersistent(boolean persistent);

    /**
     * Adds the given {@link DataFilter} to this channel.
     * @param filter the data filter to add
     * @see #removeDataFilter(DataFilter)
     * @see #getDataFilters()
     */
    void addDataFilter(DataFilter filter);

    /**
     * Removes the given {@link DataFilter} from this channel.
     * @param filter the data filter to remove
     * @return the removed data filter
     * @see #addDataFilter(DataFilter)
     */
    DataFilter removeDataFilter(DataFilter filter);

    /**
     * Returns a collection copy of the data filters for this channel.
     * @return the data filters for this channel
     */
    Collection<DataFilter> getDataFilters();

    /**
     * Adds a channel listener to this channel.
     * @param listener the listener to add
     * @see #removeListener(ChannelListener)
     */
    void addListener(ChannelListener listener);

    /**
     * Removes the channel listener from this channel.
     * @param listener the listener to remove
     * @see #addListener(ChannelListener)
     */
    void removeListener(ChannelListener listener);

    /**
     * @return whether the channel is lazy.
     * @see #setLazy(boolean)
     */
    boolean isLazy();

    /**
     * Sets the lazyness of the channel
     * @param lazy true if channel is lazy
     * @see #isLazy()
     */
    void setLazy(boolean lazy);

    /**
     * <p>Listener interface invoked during creation of the channel, to allow
     * configuration of the channel to be atomic with its creation.</p>
     */
    interface Initializer extends BayeuxListener
    {
        /**
         * <p>Callback invoked to configure the channel during its creation.</p>
         * <p>It is illegal to try to obtain the same channel within this method
         * via, for example, {@link Bayeux#getChannel(String, boolean)}, because
         * the channel is not fully constructed yet.<br/>
         * Attempting to do so will throw an {@link IllegalStateException} after
         * the {@code maxInterval} timeout configured in the Bayeux object.</p>
         *
         * @param channel the channel to configure
         */
        void configureChannel(ConfigurableChannel channel);
    }
}
