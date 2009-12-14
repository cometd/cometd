package com.webtide.wharf.io.async;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.webtide.wharf.io.ClientConnector;
import com.webtide.wharf.io.ServerConnector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class AsyncClientConnectorTest
{
    private ExecutorService threadPool;

    @Before
    public void setUp() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws Exception
    {
        threadPool.shutdown();
    }

    @Test
    @Ignore
    public void testConnect() throws Exception
    {
        InetAddress loopback = InetAddress.getByName(null);
        InetSocketAddress address = new InetSocketAddress(loopback, 0);

        AsyncConnectorListener listener = new AsyncConnectorListener()
        {
            public AsyncInterpreter connected(AsyncCoordinator coordinator)
            {
                return new TestAsyncInterpreter(coordinator);
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);
        ServerConnector serverConnector = new StandardAsyncServerConnector(address, listener, threadPool)
        {
            @Override
            protected void accepted(SocketChannel channel) throws IOException
            {
                super.accepted(channel);
                latch.countDown();
            }
        };

        try
        {
            ClientConnector clientConnector = StandardAsyncClientConnector.newInstance(new InetSocketAddress(loopback, serverConnector.getPort()), null, threadPool);
            try
            {
                Assert.assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
            }
            finally
            {
                clientConnector.close();
            }
        }
        finally
        {
            serverConnector.close();
        }
    }
}
