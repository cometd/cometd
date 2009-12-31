package org.cometd.bwtp.server;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cometd.bwtp.BWTPVersionType;
import org.cometd.bwtp.IBWTPConnection;
import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.bwtp.server.generator.ServerBWTPGenerator;
import org.cometd.bwtp.server.generator.StandardServerBWTPGenerator;
import org.cometd.bwtp.server.parser.push.ServerBWTPPushParser;
import org.cometd.wharf.async.AsyncCoordinator;
import org.cometd.wharf.async.AsyncInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class ServerBWTPAsyncInterpreter implements AsyncInterpreter
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncCoordinator coordinator;
    private final BWTPProcessor processor;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    private final ServerBWTPGenerator generator;
    private final BWTPParser parser;
    private final IBWTPConnection connection;

    public ServerBWTPAsyncInterpreter(AsyncCoordinator coordinator, BWTPProcessor processor)
    {
        this.coordinator = coordinator;
        this.processor = processor;
        this.readBuffer = ByteBuffer.allocate(1024);
        this.writeBuffer = ByteBuffer.allocate(1024);
        this.generator = new StandardServerBWTPGenerator(coordinator, writeBuffer);
        this.parser = new ServerBWTPPushParser()
        {
            @Override
            protected void upgradeRequest(String method, String uri, String version, Map<String, String> headers)
            {
                onUpgradeRequest(method, uri, version, headers);
            }
        };
        this.connection = new ServerBWTPConnection(coordinator, parser, generator);
        this.connection.addListener(processor);
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

    protected void onUpgradeRequest(String method, String uri, String version, Map<String, String> headers)
    {
        logger.debug("Interpreted upgrade request\r\n{} {} {}\r\n{}", new Object[]{method, uri, version, headers});
        try
        {
            String upgrade = headers.get("Upgrade");
            BWTPVersionType type = BWTPVersionType.from(upgrade);
            if (type != null)
            {
                Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
                // TODO: add headers for origin, etc
                generator.upgradeResponse(type, responseHeaders);

                processor.onConnect(connection);
            }
            else
            {
                // Not a BWTP upgrade request, this interpreter cannot handle plain http, so close the connection
                coordinator.close();
            }
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Could not write upgrade response because the connection was closed", x);
            coordinator.close();
        }
    }
}
