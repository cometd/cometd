package org.cometd.bwtp.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.cometd.bwtp.BWTPChannel;
import org.cometd.bwtp.BWTPConnection;
import org.cometd.bwtp.server.BWTPServer;
import org.cometd.wharf.ServerConnector;
import org.cometd.wharf.async.StandardAsyncServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * @version $Revision$ $Date$
 */
public class BWTPChannelTest
{
    private ExecutorService threadPool;
    private InetSocketAddress address;
    private ServerConnector serverConnector;

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        address = new InetSocketAddress(InetAddress.getByName(null), 0);
        serverConnector = new StandardAsyncServerConnector(address, new BWTPServer(), threadPool);
        address = new InetSocketAddress(address.getAddress(), serverConnector.getPort());
    }

    @After
    public void destroy() throws Exception
    {
        serverConnector.close();
        threadPool.shutdown();
    }

    @Test
    public void testChannel() throws Exception
    {
        BWTPClient client = new BWTPClient(threadPool);
        BWTPConnection connection = client.connect(address, "/", null);
        BWTPChannel channel = connection.open("", null);
        assertNotNull(channel.getId());
        assertNotNull(channel.getConnection());
        assertSame(connection, channel.getConnection());
        assertSame(channel, connection.findChannel(channel.getId()));
        connection.close(null);
    }
}
