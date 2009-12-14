package com.webtide.wharf.io.async;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Selector;

/**
 * <p>{@link AsyncCoordinator} coordinates the activity between the {@link AsyncEndpoint},
 * the {@link AsyncInterpreter} and the {@link SelectorManager}.</p>
 * <p>{@link AsyncCoordinator} receives I/O events from the {@link SelectorManager}, and
 * dispatches them appropriately to either the {@link AsyncEndpoint} or the {@link AsyncInterpreter}.</p>
 * <p/>
 *
 * @version $Revision$ $Date$
 */
public interface AsyncCoordinator extends SelectorManager.Listener
{
    /**
     * @param endpoint the endpoint associated with this coordinator
     */
    public void setEndpoint(AsyncEndpoint endpoint);

    /**
     * @param interpreter the interpreter associated with this coordinator
     */
    public void setInterpreter(AsyncInterpreter interpreter);

    /**
     * <p>Asks the I/O system to register (if {@code needsRead} is true) or
     * to deregister (if {@code needsRead} is false) for interest in read events.</p>
     * <p>Normally, an interpreter will call this method when it detects that
     * a request is not complete and more data needs to be read.</p>
     *
     * @param needsRead true to indicate that there is interest in receiving read events,
     *                  false to indicate that there is no interest in receiving read events.
     * @return true if the I/O system changed its status, false otherwise
     */
    public boolean needsRead(boolean needsRead);

    /**
     * <p>Asks the I/O system to register (if {@code needsWrite} is true) or
     * to deregister (if {@code needsWrite} is false) for interest in write events.</p>
     * <p>Normally, an endpoint will call this method when it detects that it cannot
     * write more bytes to the I/O system.</p>
     *
     * @param needsWrite true to indicate that there is interest in receiving read events,
     *                  false to indicate that there is no interest in receiving read events.
     * @return true if the I/O system changed its status, false otherwise
     */
    public boolean needsWrite(boolean needsWrite);

    /**
     * <p>Reads bytes from the given {@code buffer}.</p>
     * <p>Normally, an endpoint will call this method after it read bytes from the I/O system,
     * and this coordinator will forward the method call to the interpreter.</p>
     *
     * @param buffer the buffer to read bytes from
     * @see AsyncInterpreter#readFrom(ByteBuffer)
     */
    public void readFrom(ByteBuffer buffer);

    /**
     * <p>Writes bytes from the given {@code buffer}.</p>
     * <p>Normally, an interpreter will call this method after it filled the buffer with bytes
     * to write, and this coordinator will forward the call to the endpoint.</p>
     * @param buffer the buffer to write bytes from
     * @see AsyncEndpoint#writeFrom(ByteBuffer)
     */
    public void writeFrom(ByteBuffer buffer);
}
