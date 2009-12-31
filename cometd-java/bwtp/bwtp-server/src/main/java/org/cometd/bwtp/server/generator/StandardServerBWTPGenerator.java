package org.cometd.bwtp.server.generator;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.BWTPVersionType;
import org.cometd.bwtp.generator.StandardBWTPGenerator;
import org.cometd.wharf.async.AsyncCoordinator;

/**
 * @version $Revision$ $Date$
 */
public class StandardServerBWTPGenerator extends StandardBWTPGenerator implements ServerBWTPGenerator
{
    public StandardServerBWTPGenerator(AsyncCoordinator coordinator, ByteBuffer buffer)
    {
        super(coordinator, buffer, "/");
    }

    public void upgradeResponse(BWTPVersionType version, Map<String, String> headers) throws ClosedChannelException
    {
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 101 ");
        builder.append(version.getCode()).append("\r\n");
        if (headers != null)
        {
            for (Map.Entry<String, String> entry : headers.entrySet())
            {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        builder.append("\r\n");

        write(utf8Encode(builder.toString()));
        logger.debug("Written upgrade response\r\n{}", builder);
    }
}
