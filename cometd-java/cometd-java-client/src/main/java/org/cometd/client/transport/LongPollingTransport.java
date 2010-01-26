package org.cometd.client.transport;

import java.io.IOException;
import java.util.List;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.BayeuxClient;
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
    private final HttpClient _httpClient;

    public LongPollingTransport()
    {
        _httpClient = new HttpClient();
    }
    
    public LongPollingTransport(HttpClient httpClient)
    {
        _httpClient = httpClient;
    }

    @Override
    public String getName()
    {
        return "long-polling";
    }

    public boolean accept(String bayeuxVersion)
    {
        return true;
    }

    @Override
    public void init(BayeuxClient bayeux)
    {
        super.init(bayeux);
    }

    public void send(String uri, Message... messages)
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
            _httpClient.send(httpExchange);
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
                List<Message.Mutable> messages = toMessages(getResponseContent());
                notifyMessages(messages);
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
