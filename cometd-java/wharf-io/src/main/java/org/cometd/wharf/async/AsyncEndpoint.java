package org.cometd.wharf.async;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;

/**
 * <p>{@link AsyncEndpoint} hides the complexity of working with {@link SelectableChannel}s.</p>
 *
 * @version $Revision$ $Date$
 */
public interface AsyncEndpoint
{
    /**
     * <p>Registers this endpoint with the given {@code selector} for the given interest {@code operations}
     * and with the given {@code listener} as attachment.</p>
     *
     * @param selector   the selector this endpoint's channel must register with
     * @param operations the interest operations that must be registered
     * @param listener   the attachment to the registration
     * @throws ClosedChannelException if this endpoint's channel has been closed
     * @see SelectableChannel#register(Selector, int, Object)
     */
    public void registerWith(Selector selector, int operations, SelectorManager.Listener listener) throws ClosedChannelException;

    public boolean needsRead(boolean needsRead);

    public boolean needsWrite(boolean needsWrite);

    public void readInto(ByteBuffer buffer) throws ClosedChannelException;

    public void writeFrom(ByteBuffer buffer) throws ClosedChannelException;

    public void close();
}
