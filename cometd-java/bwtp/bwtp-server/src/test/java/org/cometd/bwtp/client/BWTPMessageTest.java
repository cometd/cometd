package org.cometd.bwtp.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.cometd.bwtp.BWTPChannel;
import org.cometd.bwtp.BWTPConnection;
import org.cometd.bwtp.BWTPListener;
import org.cometd.bwtp.BWTPMessageFrame;
import org.cometd.bwtp.server.BWTPServer;
import org.cometd.bwtp.server.EchoBWTPProcessor;
import org.cometd.wharf.ServerConnector;
import org.cometd.wharf.async.StandardAsyncServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$ $Date$
 */
public class BWTPMessageTest
{
    private ExecutorService threadPool;
    private InetSocketAddress address;
    private ServerConnector serverConnector;

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        address = new InetSocketAddress(InetAddress.getByName(null), 0);
        serverConnector = new StandardAsyncServerConnector(address, new BWTPServer(new EchoBWTPProcessor()), threadPool);
        address = new InetSocketAddress(address.getAddress(), serverConnector.getPort());
    }

    @After
    public void destroy() throws Exception
    {
        serverConnector.close();
        threadPool.shutdown();
    }

    @Test
    public void testMessage() throws Exception
    {
        BWTPClient client = new BWTPClient(threadPool);
        BWTPConnection connection = client.connect(address, "/", null);

        final CountDownLatch latch = new CountDownLatch(1);
        connection.addListener(new BWTPListener.Adapter()
        {
            @Override
            public void onMessage(BWTPChannel channel, BWTPMessageFrame frame)
            {
                latch.countDown();
            }
        });

        BWTPChannel channel = connection.open("", null);
        byte[] content = {0, 1, 2};
        channel.message(content);
        assertTrue(latch.await(100, TimeUnit.SECONDS));

        channel.close(null);

        connection.close(null);
    }
}
