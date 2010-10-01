package org.cometd.javascript;

import java.io.InterruptedIOException;

import org.eclipse.jetty.client.HttpClient;
import org.mozilla.javascript.ScriptableObject;

/**
 * Implementation of the XMLHttpRequest functionality using Jetty's HttpClient.
 *
 * @version $Revision$ $Date$
 */
public class XMLHttpRequestClient extends ScriptableObject
{
    private HttpClient httpClient;
    private int maxConnections;

    public XMLHttpRequestClient()
    {
    }

    public void jsConstructor(int maxConnections) throws Exception
    {
        this.maxConnections = maxConnections;
    }

    public String getClassName()
    {
        return "XMLHttpRequestClient";
    }

    public void start() throws Exception
    {
        httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(maxConnections);
        httpClient.setIdleTimeout(300000);
        httpClient.setTimeout(300000);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        httpClient.start();
    }

    public void stop() throws Exception
    {
        httpClient.stop();
    }

    public void jsFunction_send(XMLHttpRequestExchange exchange) throws Exception
    {
        exchange.send(httpClient);
        try
        {
            if (!exchange.isAsynchronous())
                exchange.await();
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }
}
