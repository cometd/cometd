package org.cometd.wharf.async;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.cometd.wharf.ServerConnector;
import org.junit.Assert;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class AsyncServerConnectorCloseAfterAcceptTest
{
    @Test
    public void testCloseAfterAccept() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            InetAddress loopback = InetAddress.getByName(null);
            InetSocketAddress address = new InetSocketAddress(loopback, 0);

            AsyncConnectorListener listener = new AsyncConnectorListener()
            {
                public AsyncInterpreter connected(AsyncCoordinator coordinator)
                {
                    return null;
                }
            };

            final CountDownLatch latch = new CountDownLatch(1);
            ServerConnector serverConnector = new StandardAsyncServerConnector(address, listener, threadPool)
            {
                @Override
                protected AsyncEndpoint newEndpoint(SocketChannel channel, AsyncCoordinator coordinator)
                {
                    return new StandardAsyncEndpoint(channel, coordinator)
                    {
                        @Override
                        public void registerWith(Selector selector, int operations, SelectorManager.Listener listener) throws ClosedChannelException
                        {
                            try
                            {
                                super.registerWith(selector, operations, listener);
                            }
                            finally
                            {
                                latch.countDown();
                            }
                        }
                    };
                }

                @Override
                protected void register(AsyncEndpoint endpoint, int operations, AsyncCoordinator coordinator)
                {
                    endpoint.close();
                    super.register(endpoint, operations, coordinator);
                }
            };
            try
            {
                Socket socket = new Socket(loopback, serverConnector.getPort());
                try
                {
                    // Wait for the SelectorManager to register the endpoint
                    Assert.assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));

                    // When the server side of a socket is closed, the client is not notified (although a TCP FIN packet arrives).
                    // Calling socket.isClosed() yields false, socket.isConnected() yields true, so no help.
                    // Writing on the output stream causes a RST from the server, but this may not be converted to
                    // a SocketException("Broken Pipe") depending on the TCP buffers on the OS, I think.
                    // If the write is big enough, eventually SocketException("Broken Pipe") is raised.
                    // The only reliable way is to read and check if we get -1.

                    int datum = socket.getInputStream().read();
                    Assert.assertEquals(-1, datum);
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
}
