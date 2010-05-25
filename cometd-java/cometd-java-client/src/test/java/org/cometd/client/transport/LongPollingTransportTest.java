package org.cometd.client.transport;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpURI;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransportTest
{
    Map<String,Object> _options;

    @Before
    public void setup()
    {
        _options=new HashMap<String, Object>();
    }

    @Test
    public void testType()
    {
        ClientTransport transport = LongPollingTransport.create(null);
        assertEquals("long-polling", transport.getName());
    }

    @Test
    public void testAccept()
    {
        ClientTransport transport = LongPollingTransport.create(null);
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
                finally
                {
                }
            }
        };
        serverThread.start();
        final HttpURI serverURI=new HttpURI("http://localhost:" + serverSocket.getLocalPort());

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                final CountDownLatch latch = new CountDownLatch(1);
                ClientTransport transport = new LongPollingTransport(_options,httpClient);
                transport.init(null,serverURI);

                long start = System.nanoTime();
                transport.send(new AbstractTransportListener()
                {
                    @Override
                    public void onMessages(List<Message.Mutable> messages)
                    {
                        latch.countDown();
                    }
                },new HashMapMessage());
                long end = System.nanoTime();

                assertTrue(TimeUnit.NANOSECONDS.toMillis(end - start) < processingTime);
                assertTrue(latch.await(2 * processingTime, TimeUnit.MILLISECONDS));
            }
            catch(Exception e)
            {
                e.printStackTrace();
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
        HttpURI serverURI = new HttpURI("http://localhost:" + serverSocket.getLocalPort());

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(_options,httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.init(null,serverURI);

                long start = System.nanoTime();
                transport.send(new AbstractTransportListener()
                {
                    @Override
                    public void onProtocolError(String info)
                    {
                        latch.countDown();
                    }
                });
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
        serverSocket.close();
        final HttpURI serverURI=new HttpURI("http://localhost:" + serverSocket.getLocalPort());

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        try
        {
            ClientTransport transport = new LongPollingTransport(_options,httpClient);
            final CountDownLatch latch = new CountDownLatch(1);
            transport.init(null,serverURI);

            transport.send(new AbstractTransportListener()
            {
                @Override
                public void onConnectException(Throwable x)
                {
                    latch.countDown();
                }
            });

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
        final HttpURI serverURI=new HttpURI("http://localhost:" + serverSocket.getLocalPort());

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(_options,httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.init(null,serverURI);

                long start = System.nanoTime();
                transport.send(new AbstractTransportListener()
                {
                    @Override
                    public void onException(Throwable x)
                    {
                        latch.countDown();
                    }
                });
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
        final HttpURI serverURI=new HttpURI("http://localhost:" + serverSocket.getLocalPort());

        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setTimeout(timeout);
            httpClient.start();

            try
            {
                ClientTransport transport = new LongPollingTransport(_options,httpClient);
                final CountDownLatch latch = new CountDownLatch(1);
                transport.init(null,serverURI);

                transport.send(new AbstractTransportListener()
                {
                    @Override
                    public void onExpire()
                    {
                        latch.countDown();
                    }
                });

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

    protected class AbstractTransportListener implements TransportListener
    {
        boolean _suppress;

        @Override
        public void onConnectException(Throwable x)
        {
            if (!_suppress)
                x.printStackTrace();
        }

        @Override
        public void onException(Throwable x)
        {
            if (!_suppress)
                x.printStackTrace();
        }

        @Override
        public void onExpire()
        {
        }

        @Override
        public void onSending(Mutable[] messages)
        {
        }

        @Override
        public void onMessages(List<Mutable> metaMessages)
        {
        }

        @Override
        public void onProtocolError(String info)
        {
        }
    }
}
