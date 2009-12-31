package org.cometd.bwtp.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.cometd.wharf.ServerConnector;
import org.cometd.wharf.async.StandardAsyncServerConnector;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class MainTest
{
    @Test
    public void testMain() throws Exception
    {
        ExecutorService threadPool = Executors.newCachedThreadPool();
        try
        {
            InetAddress loopBack = InetAddress.getByName(null);
            InetSocketAddress address = new InetSocketAddress(loopBack, 0);
            ServerConnector connector = new StandardAsyncServerConnector(address, new BWTPServer(), threadPool);

            Socket socket = new Socket(loopBack, connector.getPort());

            StringBuilder builder = new StringBuilder();
            builder.append("GET / HTTP/1.1\r\n");
            builder.append("Host: localhost:8080\r\n");
            builder.append("Upgrade: BWTP/1.0\r\n");
            builder.append("Connection: upgrade\r\n");
            builder.append("\r\n");
            builder.append("BWH 0 68 OPEN /bwtp\r\n");
            builder.append("Content-Type: application/json;charset=UTF-8\r\n");
            builder.append("Content-Language: en\r\n");


        }
        finally
        {
            threadPool.shutdown();
        }
    }
}
