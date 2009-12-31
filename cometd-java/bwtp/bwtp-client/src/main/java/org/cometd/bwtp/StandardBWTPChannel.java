package org.cometd.bwtp;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.generator.BWTPGenerator;

/**
 * @version $Revision$ $Date$
 */
public class StandardBWTPChannel implements BWTPChannel
{
    private final String id;
    private final IBWTPConnection connection;
    private final BWTPGenerator generator;

    public StandardBWTPChannel(String id, IBWTPConnection connection, BWTPGenerator generator)
    {
        this.id = id;
        this.connection = connection;
        this.generator = generator;
    }

    public String getId()
    {
        return id;
    }

    public BWTPConnection getConnection()
    {
        return connection;
    }

    public void header(Map<String, String> headers) throws ClosedChannelException
    {
        generator.header(id, headers);
    }

    public void message(byte[] content) throws ClosedChannelException
    {
        generator.message(id, content);
    }

    public void close(Map<String, String> headers)
    {
        connection.close(id, headers);
    }
}
