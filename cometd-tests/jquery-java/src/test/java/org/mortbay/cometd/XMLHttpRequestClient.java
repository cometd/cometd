package org.mortbay.cometd;

import java.io.IOException;

import org.eclipse.jetty.client.HttpClient;
import org.mozilla.javascript.ScriptableObject;

/**
 * Implementation of the XMLHttpRequest functionality using Jetty's HttpClient.
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
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
        httpClient.setSoTimeout(0);
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
    }
}
