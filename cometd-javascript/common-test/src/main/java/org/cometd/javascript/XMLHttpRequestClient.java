package org.cometd.javascript;

import java.io.IOException;
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

    public XMLHttpRequestClient()
    {
    }

    public void jsConstructor(int maxConnections) throws Exception
    {
        httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(maxConnections);
        httpClient.setIdleTimeout(300000);
        httpClient.setTimeout(300000);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        httpClient.start();
    }

    public String getClassName()
    {
        return "XMLHttpRequestClient";
    }

    public void jsFunction_send(XMLHttpRequestExchange exchange) throws IOException
    {
        httpClient.send(exchange.getHttpExchange());
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
