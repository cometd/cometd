package com.webtide.wharf.io.async;

import java.nio.ByteBuffer;

/**
 * <p>{@link AsyncInterpreter} interprets bytes read from the I/O system.</p>
 * <p>The interpretation of the bytes normally happens with push parsers, and may involve calling
 * user code to allow processing of the incoming data and production of outgoing data.</p>
 * <p>User code may be offered a blocking I/O API (such in case of servlets); in such case,
 * the interpreter must handle the concurrency properly.</p>
 *
 * @version $Revision$ $Date$
 */
public interface AsyncInterpreter
{
    public ByteBuffer getReadBuffer();

    public ByteBuffer getWriteBuffer();

    public void readFrom(ByteBuffer buffer);
}
