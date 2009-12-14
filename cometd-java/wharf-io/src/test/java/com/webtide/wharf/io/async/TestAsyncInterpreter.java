package com.webtide.wharf.io.async;

import java.nio.ByteBuffer;

import org.junit.Ignore;

/**
 * @version $Revision$ $Date$
 */
@Ignore
public class TestAsyncInterpreter implements AsyncInterpreter
{
    private final ByteBuffer readBuffer = ByteBuffer.allocate(512);
    private final ByteBuffer writeBuffer = ByteBuffer.allocate(512);

    public TestAsyncInterpreter(AsyncCoordinator coordinator)
    {
    }

    public ByteBuffer getReadBuffer()
    {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer()
    {
        return writeBuffer;
    }

    public void readFrom(ByteBuffer bytes)
    {
    }
}
