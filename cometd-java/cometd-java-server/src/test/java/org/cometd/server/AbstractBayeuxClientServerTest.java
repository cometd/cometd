package org.cometd.server;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractBayeuxClientServerTest extends AbstractBayeuxServerTest
{
    protected HttpClient httpClient;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        httpClient = new HttpClient();
        httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        httpClient.stop();
        super.tearDown();
    }

    protected String extractClientId(String handshake)
    {
        Matcher matcher = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"").matcher(handshake);
        assertTrue(matcher.find());
        String clientId = matcher.group(1);
        assertTrue(clientId.length() > 0);
        return clientId;
    }

    protected ContentExchange newBayeuxExchange(String requestBody) throws UnsupportedEncodingException
    {
        ContentExchange result = new ContentExchange(true);
        result.setURL(cometdURL);
        result.setMethod(HttpMethods.POST);
        result.setRequestContentType("application/json;charset=UTF-8");
        result.setRequestContent(new ByteArrayBuffer(requestBody, "UTF-8"));
        return result;
    }
}
