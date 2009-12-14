package com.webtide.wharf.io.async;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class AsyncServerConnectorWriteZeroTest extends TestCase
{
    public void testWriteZero() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            AsyncConnectorListener listener = new AsyncConnectorListener()
            {
                public AsyncInterpreter connected(final AsyncCoordinator coordinator)
                {
                    return new TestAsyncInterpreter(coordinator)
                    {
                        public void readFrom(ByteBuffer buffer)
                        {
                            // Read and echo back
                            ByteBuffer writeBuffer = getWriteBuffer();
                            writeBuffer.put(buffer);
                            writeBuffer.flip();
                            coordinator.writeFrom(writeBuffer);
                        }
                    };
                }
            };

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger writes = new AtomicInteger();
            final AtomicInteger needWrites = new AtomicInteger();
            InetAddress loopback = InetAddress.getByName(null);
            InetSocketAddress address = new InetSocketAddress(loopback, 0);

            StandardAsyncServerConnector serverConnector = new StandardAsyncServerConnector(address, listener, threadPool)
            {
                @Override
                protected AsyncEndpoint newEndpoint(SocketChannel channel, AsyncCoordinator coordinator)
                {
                    return new StandardAsyncEndpoint(channel, coordinator)
                    {
                        private final AtomicInteger writes = new AtomicInteger();

                        @Override
                        protected int writeAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
                        {
                            if (this.writes.compareAndSet(0, 1))
                            {
                                // In the first aggressive write, we simulate a zero bytes write
                                return 0;
                            }
                            else if (this.writes.compareAndSet(1, 2))
                            {
                                // In the second aggressive write, simulate 1 byte write
                                ByteBuffer newBuffer = ByteBuffer.allocate(1);
                                newBuffer.put(buffer.get());
                                channel.write(newBuffer);
                                return newBuffer.capacity();
                            }
                            else
                            {
                                int result = super.writeAggressively(channel, buffer);
                                latch.countDown();
                                return result;
                            }
                        }
                    };
                }

                @Override
                protected AsyncCoordinator newCoordinator()
                {
                    return new StandardAsyncCoordinator(getSelector(), getThreadPool())
                    {
                        @Override
                        public void writeFrom(ByteBuffer buffer)
                        {
                            writes.incrementAndGet();
                            super.writeFrom(buffer);
                        }

                        @Override
                        public boolean needsWrite(boolean needsWrite)
                        {
                            needWrites.incrementAndGet();
                            return super.needsWrite(needsWrite);
                        }
                    };
                }
            };

            try
            {
                Socket socket = new Socket(loopback, serverConnector.getPort());
                try
                {
                    OutputStream output = socket.getOutputStream();
                    byte[] bytes = "HELLO".getBytes("UTF-8");
                    output.write(bytes);
                    output.flush();

                    assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));

                    // One write call from the interpreter
                    assertEquals(1, writes.get());
                    // Four needsWrite calls:
                    // after writing 0 bytes to enable the writes, then to disable;
                    // after writing 1 byte to enable the writes, then to disable
                    assertEquals(4, needWrites.get());
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
