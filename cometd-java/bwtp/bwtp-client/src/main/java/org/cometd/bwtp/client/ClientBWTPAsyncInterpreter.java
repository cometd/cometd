package org.cometd.bwtp.client;

import java.nio.ByteBuffer;

import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.wharf.async.AsyncCoordinator;
import org.cometd.wharf.async.AsyncInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class ClientBWTPAsyncInterpreter implements AsyncInterpreter
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncCoordinator coordinator;
    private final BWTPParser parser;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;

    public ClientBWTPAsyncInterpreter(AsyncCoordinator coordinator, BWTPParser parser)
    {
        this.coordinator = coordinator;
        this.parser = parser;
        this.readBuffer = ByteBuffer.allocate(1024);
        this.writeBuffer = ByteBuffer.allocate(1024);
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
        while (buffer.hasRemaining())
        {
            logger.debug("Interpreting {} bytes", buffer.remaining());
            parser.parse(buffer);
        }
        coordinator.needsRead(true);
    }
}
