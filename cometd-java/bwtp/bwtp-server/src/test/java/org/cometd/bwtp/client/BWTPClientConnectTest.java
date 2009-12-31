package org.cometd.bwtp.client;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.cometd.bwtp.BWTPConnection;
import org.cometd.bwtp.server.BWTPServer;
import org.cometd.wharf.ServerConnector;
import org.cometd.wharf.async.StandardAsyncServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * @version $Revision$ $Date$
 */
public class BWTPClientConnectTest
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
    public void destroy()
    {
        serverConnector.close();
        threadPool.shutdown();
    }

    @Test
    public void testConnectNoServer() throws Exception
    {
        serverConnector.close();

        BWTPClient client = new BWTPClient(threadPool);
        try
        {
            client.connect(address, "/", null);
            fail();
        }
        catch (ConnectException ignored)
        {
        }
    }

    @Test
    public void testConnect() throws Exception
    {
        BWTPClient client = new BWTPClient(threadPool);
        BWTPConnection connection = client.connect(address, "/", null);
        assertNotNull(connection);
        connection.close(null);
    }

    // TODO: test server timing out on upgrade
}
