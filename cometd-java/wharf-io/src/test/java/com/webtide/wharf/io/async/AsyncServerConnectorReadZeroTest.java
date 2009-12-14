package com.webtide.wharf.io.async;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class AsyncServerConnectorReadZeroTest extends TestCase
{
    public void testReadZero() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            final AtomicReference<ByteBuffer> data = new AtomicReference<ByteBuffer>();
            final CountDownLatch latch = new CountDownLatch(1);
            AsyncConnectorListener listener = new AsyncConnectorListener()
            {
                public AsyncInterpreter connected(AsyncCoordinator coordinator)
                {
                    return new TestAsyncInterpreter(coordinator)
                    {
                        public void readFrom(ByteBuffer bytes)
                        {
                            data.set(bytes);
                            latch.countDown();
                        }
                    };
                }
            };

            final AtomicInteger reads = new AtomicInteger();
            final AtomicInteger needReads = new AtomicInteger();
            InetAddress loopback = InetAddress.getByName(null);
            InetSocketAddress address = new InetSocketAddress(loopback, 0);
            StandardAsyncServerConnector serverConnector = new StandardAsyncServerConnector(address, listener, threadPool)
            {
                @Override
                protected AsyncEndpoint newEndpoint(SocketChannel channel, AsyncCoordinator coordinator)
                {
                    return new StandardAsyncEndpoint(channel, coordinator)
                    {
                        private final AtomicInteger reads = new AtomicInteger();

                        @Override
                        protected int readAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
                        {
                            if (this.reads.compareAndSet(0, 1))
                            {
                                // In the first greedy read, we simulate a zero bytes read
                                return 0;
                            }
                            else
                            {
                                return super.readAggressively(channel, buffer);
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
                        public void readFrom(ByteBuffer bytes)
                        {
                            reads.incrementAndGet();
                            super.readFrom(bytes);
                        }

                        @Override
                        public boolean needsRead(boolean needsRead)
                        {
                            needReads.incrementAndGet();
                            return super.needsRead(needsRead);
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

                    assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
                    assertNotNull(data.get());
                    ByteBuffer buffer = data.get();
                    byte[] result = new byte[buffer.remaining()];
                    buffer.get(result);
                    assertTrue(Arrays.equals(bytes, result));
                    // One read call, since with 0 bytes read we don't call it
                    assertEquals(1, reads.get());
                    // Three needsRead calls: at beginning to disable the reads,
                    // after reading 0 to re-enable the reads, and again to disable
                    // the reads when the bytes are read.
                    assertEquals(3, needReads.get());
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
