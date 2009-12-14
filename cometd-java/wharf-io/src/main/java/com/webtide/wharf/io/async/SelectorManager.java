package com.webtide.wharf.io.async;

/**
 * <p>{@link SelectorManager} hides the complexity of working with {@link java.nio.channels.Selector}.</p>
 * <p>A {@link SelectorManager} associates an {@link AsyncEndpoint} to a {@link Listener} so that
 * when the I/O system associated to the endpoint signals readiness for I/O events, the listener is
 * notified. This normally results in the listener to call the endpoint to perform the actual I/O.</p>
 *
 * @version $Revision$ $Date$
 */
public interface SelectorManager
{
    public void register(AsyncEndpoint endpoint, int operations, Listener listener);

    public void wakeup();

    public void close();

    /**
     * <p>The interface for receiving events from the {@link SelectorManager}.</p>
     */
    public interface Listener
    {
        /**
         * <p>Invoked when the {@link SelectorManager} detects that the I/O system is ready to read.</p>
         * @see #writeReady()
         */
        public void readReady();

        /**
         * <p>Invoked when the {@link SelectorManager} detects that the I/O system is ready to write.</p>
         * @see #readReady()
         */
        public void writeReady();
    }
}
