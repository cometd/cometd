package org.cometd.bwtp.client.generator;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.generator.StandardBWTPGenerator;
import org.cometd.wharf.async.AsyncCoordinator;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBWTPGenerator extends StandardBWTPGenerator implements ClientBWTPGenerator
{
    public StandardClientBWTPGenerator(AsyncCoordinator coordinator, ByteBuffer buffer, String basePath)
    {
        super(coordinator, buffer, basePath);
    }

    public void upgradeRequest(String path, Map<String, String> headers) throws ClosedChannelException
    {
        StringBuilder builder = new StringBuilder();
        builder.append("GET ");
        builder.append(path);
        builder.append(" HTTP/1.1\r\n");
        if (headers != null)
        {
            for (Map.Entry<String, String> entry : headers.entrySet())
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        builder.append("\r\n");

        logger.debug("Writing upgrade request\r\n{}", builder);
        write(utf8Encode(builder.toString()));
    }
}
