package org.cometd.bayeux.client.transport;

import java.io.IOException;
import java.util.Map;

import org.cometd.bayeux.client.MetaMessage;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransport extends AbstractTransport
{
    private final String uri;
    private final HttpClient httpClient;

    public LongPollingTransport(String uri, HttpClient httpClient)
    {
        this.uri = uri;
        this.httpClient = httpClient;
    }

    public String getType()
    {
        return "long-polling";
    }

    public boolean accept(String bayeuxVersion)
    {
        return true;
    }

    public void init()
    {
    }

    public void send(MetaMessage.Mutable... messages)
    {
        HttpExchange httpExchange = new TransportExchange();
        httpExchange.setMethod("POST");
        // TODO: handle extra path for handshake, connect and disconnect
        httpExchange.setURL(uri);

        String content = JSON.toString(messages);
        httpExchange.setRequestContentType("application/json;charset=UTF-8");
        try
        {
            httpExchange.setRequestContent(new ByteArrayBuffer(content, "UTF-8"));
            httpClient.send(httpExchange);
        }
        catch (Exception x)
        {
            notifyException(x);
        }
    }

    private class TransportExchange extends ContentExchange
    {
        private TransportExchange()
        {
            super(true);
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (getResponseStatus() == 200)
            {
                // TODO: this must be improved (in all transports)
                MetaMessage.Mutable[] result;
                Object content = JSON.parse(getResponseContent());
                if (content instanceof Map)
                {
                    Map<String, Object> map = (Map<String, Object>)content;
                    result = new MetaMessage.Mutable[]{newMetaMessage(map)};
                }
                else if (content instanceof Object[])
                {
                    Object[] maps = (Object[])content;
                    result = new MetaMessage.Mutable[maps.length];
                    for (int i = 0; i < maps.length; i++)
                    {
                        Map<String, Object> map = (Map<String, Object>)maps[i];
                        result[i] = newMetaMessage(map);
                    }
                }
                else
                {
                    result = new MetaMessage.Mutable[0];
                }
                notifyMetaMessages(result);
            }
            else
            {
                notifyProtocolError();
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            notifyConnectException(x);
        }

        @Override
        protected void onException(Throwable x)
        {
            notifyException(x);
        }

        @Override
        protected void onExpire()
        {
            notifyExpire();
        }
    }
}
