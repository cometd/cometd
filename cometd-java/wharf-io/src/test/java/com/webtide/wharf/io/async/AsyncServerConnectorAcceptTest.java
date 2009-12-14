package com.webtide.wharf.io.async;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.webtide.wharf.io.ServerConnector;
import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class AsyncServerConnectorAcceptTest extends TestCase
{
    public void testBlockingAccept() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            InetSocketAddress address = new InetSocketAddress("localhost", 0);

            final CountDownLatch latch = new CountDownLatch(1);
            ServerConnector serverConnector = new StandardAsyncServerConnector(address, null, threadPool)
            {
                @Override
                protected void accepted(SocketChannel channel) throws IOException
                {
                    latch.countDown();
                }
            };
            try
            {
                Socket socket = new Socket(address.getHostName(), serverConnector.getPort());
                try
                {
                    assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
                }
                finally
                {
                    socket.close();
                }
            }
            finally
            {
                serverConnector.close();
            }
        }
        finally
        {
            threadPool.shutdown();
        }
    }

    public void testNonBlockingAccept() throws Exception
    {
        // TODO
    }
}
