package org.cometd.bwtp.client;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.Executor;

import org.cometd.bwtp.BWTPConnection;

/**
 * @version $Revision$ $Date$
 */
public class BWTPClient
{
    private final Executor threadPool;

    public BWTPClient(Executor threadPool)
    {
        this.threadPool = threadPool;
    }

    public BWTPConnection connect(InetSocketAddress address, String path, Map<String, String> headers)
            throws ConnectException, ClosedChannelException
    {
        ClientBWTPConnection connection = new ClientBWTPConnection(address, path, headers, threadPool);
        connection.connect();
        return connection;
    }
}
