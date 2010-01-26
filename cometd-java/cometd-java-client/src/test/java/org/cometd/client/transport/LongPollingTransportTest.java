package org.cometd.client.transport;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.client.transport.AbstractTransportListener;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.HttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransportTest
{
    @Test
    public void testType()
    {
        ClientTransport transport = new LongPollingTransport(null);
        assertEquals("long-polling", transport.getName());
    }

    @Test
    public void testAccept()
    {
        ClientTransport transport = new LongPollingTransport(null);
        assertTrue(transport.accept("1.0"));
    }

    @Test
    public void testSendWithResponse200() throws Exception
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
                            "Content-Type: application/json;charset=UTF-8\r\n" +
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
        final String serverURI = "http://localhost:" + serverSocket.getLocalPort();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                final CountDownLatch latch = new CountDownLatch(1);
                ClientTransport transport = new LongPollingTransport(httpClient);
                transport.addListener(new AbstractTransportListener()
                {
                    @Override
                    public void onMessages(List<Message.Mutable> messages)
                    {
                        latch.countDown();
                    }
                });

                long start = System.nanoTime();
                transport.send(serverURI);
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
    public void testSendWithResponse500() throws Exception
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
        String serverURI = "http://localhost:" + serverSocket.getLocalPort();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.addListener(new AbstractTransportListener()
                {
                    @Override
                    public void onProtocolError()
                    {
                        latch.countDown();
                    }
                });

                long start = System.nanoTime();
                transport.send(serverURI);
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
    public void testSendWithServerDown() throws Exception
    {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        String serverURI = "http://localhost:" + port;

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        try
        {
            ClientTransport transport = new LongPollingTransport(httpClient);
            final CountDownLatch latch = new CountDownLatch(1);
            transport.addListener(new AbstractTransportListener()
            {
                @Override
                public void onConnectException(Throwable x)
                {
                    latch.countDown();
                }
            });

            transport.send(serverURI);

            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testSendWithServerCrash() throws Exception
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
        String serverURI = "http://localhost:" + serverSocket.getLocalPort();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.addListener(new AbstractTransportListener()
                {
                    @Override
                    public void onException(Throwable x)
                    {
                        latch.countDown();
                    }
                });

                long start = System.nanoTime();
                transport.send(serverURI);
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
    public void testSendWithServerExpire() throws Exception
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
                            "Content-Type: application/json;charset=UTF-8\r\n" +
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
        String serverURI = "http://localhost:" + serverSocket.getLocalPort();

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setTimeout(timeout);
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.addListener(new AbstractTransportListener()
                {
                    @Override
                    public void onExpire()
                    {
                        latch.countDown();
                    }
                });

                transport.send(serverURI);

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
