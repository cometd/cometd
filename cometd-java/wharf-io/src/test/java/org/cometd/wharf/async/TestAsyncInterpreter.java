package org.cometd.wharf.async;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import org.junit.Ignore;

/**
 * @version $Revision$ $Date$
 */
@Ignore
public class TestAsyncInterpreter implements AsyncInterpreter
{
    private final ByteBuffer readBuffer = ByteBuffer.allocate(512);
    private final ByteBuffer writeBuffer = ByteBuffer.allocate(512);
    private final AsyncCoordinator coordinator;

    public TestAsyncInterpreter(AsyncCoordinator coordinator)
    {
        this.coordinator = coordinator;
    }

    public ByteBuffer getReadBuffer()
    {
        return readBuffer;
    }

    public ByteBuffer getWriteBuffer()
    {
        return writeBuffer;
    }

    public void readFrom(ByteBuffer buffer)
    {
        writeBuffer.clear();
        writeBuffer.put(buffer);
        writeBuffer.flip();
        try
        {
            coordinator.writeFrom(writeBuffer);
        }
        catch (ClosedChannelException x)
        {
            x.printStackTrace();
        }
    }
}
