package org.cometd.wharf.async;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.wharf.ClientConnector;
import org.cometd.wharf.ServerConnector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
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
    public void testConnect() throws Exception
    {
        InetAddress loopback = InetAddress.getByName(null);
        InetSocketAddress address = new InetSocketAddress(loopback, 0);

        final AtomicReference<AsyncCoordinator> serverCoordinatorRef = new AtomicReference<AsyncCoordinator>();
        AsyncConnectorListener serverListener = new AsyncConnectorListener()
        {
            public AsyncInterpreter connected(AsyncCoordinator coordinator)
            {
                serverCoordinatorRef.set(coordinator);
                return new TestAsyncInterpreter(coordinator);
            }
        };

        final AtomicReference<AsyncCoordinator> clientCoordinatorRef = new AtomicReference<AsyncCoordinator>();
        AsyncConnectorListener clientListener = new AsyncConnectorListener()
        {
            public AsyncInterpreter connected(AsyncCoordinator coordinator)
            {
                clientCoordinatorRef.set(coordinator);
                return new TestAsyncInterpreter(coordinator);
            }
        };

        final AtomicReference<SocketChannel> channelRef = new AtomicReference<SocketChannel>();
        final CountDownLatch latch = new CountDownLatch(1);
        ServerConnector serverConnector = new StandardAsyncServerConnector(address, serverListener, threadPool)
        {
            @Override
            protected void accepted(SocketChannel channel) throws IOException
            {
                super.accepted(channel);
                channelRef.set(channel);
                latch.countDown();
            }
        };

        try
        {
            ClientConnector clientConnector = new StandardAsyncClientConnector(clientListener, threadPool);
            try
            {
                clientConnector.connect(new InetSocketAddress(loopback, serverConnector.getPort()));
                Assert.assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));

                clientCoordinatorRef.get().writeFrom(ByteBuffer.wrap(new byte[]{0, 1, 2}));

                serverConnector.close();

                Thread.sleep(5000);
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
