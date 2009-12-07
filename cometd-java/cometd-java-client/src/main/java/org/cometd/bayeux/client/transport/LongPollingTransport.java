package org.cometd.bayeux.client.transport;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import bayeux.MetaMessage;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransport implements Transport
{
    private final HttpClient httpClient;

    public LongPollingTransport(HttpClient httpClient)
    {
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

    public void send(Exchange exchange, boolean synchronous)
    {
        HttpExchange httpExchange = new TransportExchange(exchange);
        httpExchange.setMethod("POST");
        // TODO: handle extra path for handshake, connect and disconnect
        httpExchange.setURL(exchange.getURI().toString());

        String content = JSON.toString(exchange.getRequests());
        httpExchange.setRequestContentType("application/json");
        try
        {
            httpExchange.setRequestContent(new ByteArrayBuffer(content, "UTF-8"));
            httpClient.send(httpExchange);

            if (synchronous)
                httpExchange.waitForDone();
        }
        catch (Exception x)
        {
            // TODO: call exchange.failure(); instead
            throw new TransportException(x);
        }
    }

    private class TransportExchange extends ContentExchange
    {
        private final Exchange exchange;

        private TransportExchange(Exchange exchange)
        {
            super(true);
            this.exchange = exchange;
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (getResponseStatus() == 200)
            {
                // TODO: parse JSON response into MetaMessages
                MetaMessage[] responses = null;
                exchange.success(responses);
            }
            else
            {
                // TODO
                exchange.failure(null);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            exchange.failure(new TransportException(x));
        }

        @Override
        protected void onException(Throwable x)
        {
            exchange.failure(new TransportException(x));
        }

        @Override
        protected void onExpire()
        {
            exchange.failure(new TransportException(new TimeoutException()));
        }
    }
}
