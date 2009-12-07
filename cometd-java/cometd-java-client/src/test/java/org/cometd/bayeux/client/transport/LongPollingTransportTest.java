package org.cometd.bayeux.client.transport;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import bayeux.MetaMessage;
import org.eclipse.jetty.client.HttpClient;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransportTest
{
    @Test
    public void testType()
    {
        Transport transport = new LongPollingTransport(null);
        assertEquals("long-polling", transport.getType());
    }

    @Test
    public void testAccept()
    {
        Transport transport = new LongPollingTransport(null);
        assertTrue(transport.accept("1.0"));
    }

    @Test
    public void testSyncSendWithResponse200() throws Exception
    {
        testSendWithResponse200(true);
    }

    @Test
    public void testAsyncSendWithResponse200() throws Exception
    {
        testSendWithResponse200(false);
    }

    public void testSendWithResponse200(boolean synchronous) throws Exception
    {
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<Exception>();
        Thread serverThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    Thread.sleep(processingTime);

                    OutputStream output = socket.getOutputStream();
                    output.write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: 2\r\n" +
                            "\r\n" +
                            "[]").getBytes("UTF-8"));
                    output.flush();

                    socket.close();
                }
                catch (Exception x)
                {
                    serverException.set(x);
                }
            }
        };
        serverThread.start();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                Transport transport = new LongPollingTransport(httpClient);

                final CountDownLatch latch = new CountDownLatch(1);
                Exchange exchange = new Exchange("http://localhost:" + serverSocket.getLocalPort())
                {
                    public void success(MetaMessage[] responses)
                    {
                        latch.countDown();
                    }

                    public void failure(TransportException reason)
                    {
                    }
                };

                long start = System.nanoTime();
                transport.send(exchange, synchronous);
                long end = System.nanoTime();

                if (synchronous)
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(end - start) >= processingTime);
                else
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(end - start) < processingTime);

                assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            serverThread.join();
            assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testAsyncSendWithResponse500() throws Exception
    {
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<Exception>();
        Thread serverThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    Thread.sleep(processingTime);

                    OutputStream output = socket.getOutputStream();
                    output.write((
                            "HTTP/1.1 500 Internal Server Error\r\n" +
                            "Connection: close\r\n" +
                            "\r\n").getBytes("UTF-8"));
                    output.flush();

                    socket.close();
                }
                catch (Exception x)
                {
                    serverException.set(x);
                }
            }
        };
        serverThread.start();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                Transport transport = new LongPollingTransport(httpClient);

                final CountDownLatch latch = new CountDownLatch(1);
                Exchange exchange = new Exchange("http://localhost:" + serverSocket.getLocalPort())
                {
                    public void success(MetaMessage[] responses)
                    {
                    }

                    public void failure(TransportException reason)
                    {
                        latch.countDown();
                    }
                };

                long start = System.nanoTime();
                transport.send(exchange, false);
                long end = System.nanoTime();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(end - start) < processingTime);

                assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            serverThread.join();
            assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testAsyncSendWithServerDown() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        try
        {
            Transport transport = new LongPollingTransport(httpClient);

            final CountDownLatch latch = new CountDownLatch(1);
            Exchange exchange = new Exchange("http://localhost:" + port)
            {
                public void success(MetaMessage[] responses)
                {
                }

                public void failure(TransportException reason)
                {
                    latch.countDown();
                }
            };

            transport.send(exchange, false);

            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testAsyncSendWithServerCrash() throws Exception
    {
        final long processingTime = 500;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<Exception>();
        Thread serverThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    Thread.sleep(processingTime);

                    socket.close();
                }
                catch (Exception x)
                {
                    serverException.set(x);
                }
            }
        };
        serverThread.start();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                Transport transport = new LongPollingTransport(httpClient);

                final CountDownLatch latch = new CountDownLatch(1);
                Exchange exchange = new Exchange("http://localhost:" + serverSocket.getLocalPort())
                {
                    public void success(MetaMessage[] responses)
                    {
                    }

                    public void failure(TransportException reason)
                    {
                        latch.countDown();
                    }
                };

                long start = System.nanoTime();
                transport.send(exchange, false);
                long end = System.nanoTime();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(end - start) < processingTime);

                assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            serverThread.join();
            assertNull(serverException.get());
            serverSocket.close();
        }
    }

    @Test
    public void testAsyncSendWithServerExpire() throws Exception
    {
        final long timeout = 1000;
        final ServerSocket serverSocket = new ServerSocket(0);
        final AtomicReference<Exception> serverException = new AtomicReference<Exception>();
        Thread serverThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    Thread.sleep(2 * 1000);

                    OutputStream output = socket.getOutputStream();
                    output.write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: 2\r\n" +
                            "\r\n" +
                            "[]").getBytes("UTF-8"));
                    output.flush();

                    socket.close();
                }
                catch (Exception x)
                {
                    serverException.set(x);
                }
            }
        };
        serverThread.start();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setTimeout(timeout);
            httpClient.start();

            try
            {
                Transport transport = new LongPollingTransport(httpClient);

                final CountDownLatch latch = new CountDownLatch(1);
                Exchange exchange = new Exchange("http://localhost:" + serverSocket.getLocalPort())
                {
                    public void success(MetaMessage[] responses)
                    {
                    }

                    public void failure(TransportException reason)
                    {
                        latch.countDown();
                    }
                };

                transport.send(exchange, false);

                assertTrue(latch.await(2 * timeout, TimeUnit.MILLISECONDS));
            }
            finally
            {
                httpClient.stop();
            }
        }
        finally
        {
            serverThread.join();
            assertNull(serverException.get());
            serverSocket.close();
        }
    }
}
