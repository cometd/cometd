package org.cometd.websocket.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.cometd.websocket.Message;
import org.cometd.websocket.MessageType;
import org.cometd.websocket.TextMessage;
import org.junit.After;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketClientTest
{
    private ExecutorService threadPool;

    @Before
    public void initThreadPool()
    {
        threadPool = Executors.newCachedThreadPool();
    }

    @After
    public void destroyThreadPool()
    {
        threadPool.shutdown();
    }

//    @Test
    public void testClientHandshake() throws Exception
    {
        final ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        threadPool.execute(new Runnable()
        {
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    InputStream input = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    int read = input.read(buffer);

                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 101 Web Socket Protocol Handshake\r\n" +
                            "Upgrade: WebSocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "\r\n").getBytes("UTF-8"));
                    output.flush();
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                }
            }
        });


        try
        {
            URI uri = new URI("ws://localhost:" + port + "/");
            Client client = new StandardClient(uri, null, threadPool);
            final CountDownLatch latch = new CountDownLatch(1);
            client.registerListener(new Listener.Adapter()
            {
                @Override
                public void onOpen(Map<String, String> headers)
                {
                    latch.countDown();
                }
            });

            Session session = client.open();
            assertNotNull(session);
            try
            {

                assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
            }
            finally
            {
                session.close();
            }
        }
        finally
        {
            serverSocket.close();
        }
    }

    @Test
    public void testClientHandshakeAndMessage() throws Exception
    {
        final ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        threadPool.execute(new Runnable()
        {
            public void run()
            {
                try
                {
                    Socket socket = serverSocket.accept();

                    InputStream input = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    int read = input.read(buffer);

                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 101 Web Socket Protocol Handshake\r\n" +
                            "Upgrade: WebSocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "\r\n").getBytes("UTF-8"));
                    output.flush();
                    output.write(MessageType.TEXT.asByte());
                    output.write("Hello".getBytes("UTF-8"));
                    output.write(TextMessage.END);
                    output.flush();
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                }
            }
        });


        try
        {
            URI uri = new URI("ws://localhost:" + port + "/");
            Client client = new StandardClient(uri, null, threadPool);
            final CountDownLatch latch = new CountDownLatch(1);
            client.registerListener(new Listener.Adapter()
            {
                @Override
                public void onMessage(Message message)
                {
                    latch.countDown();
                }
            });

            Session session = client.open();
            assertNotNull(session);
            try
            {
                assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
            }
            finally
            {
                session.close();
            }
        }
        finally
        {
            serverSocket.close();
        }
    }
}
