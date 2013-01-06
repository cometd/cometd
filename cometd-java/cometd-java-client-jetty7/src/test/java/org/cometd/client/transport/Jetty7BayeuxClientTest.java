package org.cometd.client.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import junit.framework.Assert;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.client.HttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class Jetty7BayeuxClientTest
{
    @Rule
    public final TestWatcher testName = new TestWatcher()
    {
        @Override
        protected void starting(Description description)
        {
            super.starting(description);
            System.err.printf("Running %s.%s%n", description.getTestClass().getName(), description.getMethodName());
        }
    };
    protected ServerSocket connector;
    protected HttpClient httpClient;
    protected String cometdURL;

    @Before
    public void prepare() throws Exception
    {
        connector = new ServerSocket(0);

        int port = connector.getLocalPort();
        cometdURL = "http://localhost:" + port + "/cometd";

        httpClient = new HttpClient();
        httpClient.start();
    }

    protected BayeuxClient newBayeuxClient()
    {
        BayeuxClient client = new BayeuxClient(cometdURL, new Jetty7LongPollingTransport(null, httpClient));
        client.setDebugEnabled(debugTests());
        return client;
    }

    protected void disconnectBayeuxClient(BayeuxClient client)
    {
        client.disconnect(1000);
    }

    @After
    public void stopServer() throws Exception
    {
        if (httpClient != null)
            httpClient.stop();
        connector.close();
    }

    protected boolean debugTests()
    {
        return Boolean.getBoolean("debugTests");
    }

    @Test
    public void test() throws Exception
    {
        if (debugTests())
            System.err.println("Server listening on " + connector.getLocalSocketAddress());

        BayeuxClient client = newBayeuxClient();

        client.handshake();

        Socket server = connector.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(server.getInputStream(), "UTF-8"));
        OutputStream output = server.getOutputStream();

        String contentLengthName = "content-length:";
        int contentLength = 0;
        String line;
        while ((line = input.readLine()) != null)
        {
            if (line.regionMatches(true, 0, contentLengthName, 0, contentLengthName.length()))
                contentLength = Integer.parseInt(line.substring(contentLengthName.length()).trim());
            if (line.trim().isEmpty())
                break;
        }
        // One more read for the body
        byte[] buffer = new byte[contentLength];
        for (int i = 0; i < buffer.length; ++i)
            buffer[i] = (byte)(input.read() & 0xFF);
        line = new String(buffer, "UTF-8");
        Assert.assertTrue(line.contains("\"/meta/handshake\""));

        String reply = "[{" +
                "\"id\":\"1\"," +
                "\"version\":\"1.0\"," +
                "\"clientId\":\"9876543210\"," +
                "\"channel\":\"/meta/handshake\"," +
                "\"supportedConnectionTypes\":[\"long-polling\"]," +
                "\"successful\":true" +
                "}]";
        String response = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json;charset=UTF-8\r\n" +
                "Set-Cookie: BAYEUX_BROWSER=0123456789;Path=/\r\n" +
                "Content-Length:" + reply.length() + "\r\n" +
                "\r\n" +
                reply;
        output.write(response.getBytes("UTF-8"));
        output.flush();

        String cookieName = "Cookie:";
        boolean cookiePresent = false;
        while ((line = input.readLine()) != null)
        {
            if (line.regionMatches(true, 0, contentLengthName, 0, contentLengthName.length()))
                contentLength = Integer.parseInt(line.substring(contentLengthName.length()).trim());
            if (line.regionMatches(true, 0, cookieName, 0, cookieName.length()))
                cookiePresent = true;
            if (line.trim().isEmpty())
                break;
        }
        Assert.assertTrue(cookiePresent);
        // One more read for the body
        buffer = new byte[contentLength];
        for (int i = 0; i < buffer.length; ++i)
            buffer[i] = (byte)(input.read() & 0xFF);
        line = new String(buffer, "UTF-8");
        Assert.assertTrue(line.contains("\"/meta/connect\""));

        reply = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"successful\":true," +
                "\"advice\":{\"reconnect\":\"none\"}" +
                "}]";
        response = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json;charset=UTF-8\r\n" +
                "Content-Length:" + reply.length() + "\r\n" +
                "\r\n" +
                reply;
        output.write(response.getBytes("UTF-8"));
        output.flush();

        server.close();

        disconnectBayeuxClient(client);
    }
}
