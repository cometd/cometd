package org.cometd.client.transport;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransport extends ClientTransport
{
    public static LongPollingTransport create(Map<String, Object> options)
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(5000);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setMaxConnectionsPerAddress(32768);
        return create(options, httpClient);
    }

    public static LongPollingTransport create(Map<String, Object> options, HttpClient httpClient)
    {
        LongPollingTransport transport = new LongPollingTransport(options, httpClient);
        if (!httpClient.isStarted())
        {
            try
            {
                httpClient.start();
            }
            catch (Exception x)
            {
                throw new RuntimeException(x);
            }
        }
        return transport;
    }

    private final HttpClient _httpClient;
    private volatile BayeuxClient _bayeuxClient;
    private volatile HttpURI _uri;
    private volatile boolean _appendMessageType;

    public LongPollingTransport(Map<String,Object> options, HttpClient httpClient)
    {
        super("long-polling",options);
        _httpClient = httpClient;
    }

    public boolean accept(String bayeuxVersion)
    {
        return true;
    }

    @Override
    public void init(BayeuxClient bayeux, HttpURI uri)
    {
        _bayeuxClient = bayeux;
        _uri = uri;
        Pattern uriRegexp = Pattern.compile("(^https?://(([^:/\\?#]+)(:(\\d+))?))?([^\\?#]*)(.*)?");
        Matcher uriMatcher = uriRegexp.matcher(uri.toString());
        if (uriMatcher.matches())
        {
            String afterPath = uriMatcher.group(7);
            _appendMessageType = afterPath == null || afterPath.trim().length() == 0;
        }
        super.init(bayeux, uri);
    }

    @Override
    public void send(final TransportListener listener, Message.Mutable... messages)
    {
        HttpExchange httpExchange = new TransportExchange(listener, messages);
        httpExchange.setMethod("POST");

        httpExchange.setURL(_uri.toString());
        if (_appendMessageType && messages.length == 1 && messages[0].isMeta())
        {
            String type = messages[0].getChannel().substring(Channel.META.length());
            String url = _uri.toString();
            if (url.endsWith("/"))
                url = url.substring(0, url.length() - 1);
            url += type;
            httpExchange.setURL(url);
        }

        String content = JSON.toString(messages);
        httpExchange.setRequestContentType("application/json;charset=UTF-8");
        try
        {
            httpExchange.setRequestContent(new ByteArrayBuffer(content, "UTF-8"));
            if (_bayeuxClient != null)
                _bayeuxClient.customize(httpExchange);
            _httpClient.send(httpExchange);
        }
        catch (Exception x)
        {
            listener.onException(x);
        }
    }

    private class TransportExchange extends ContentExchange
    {
        private final TransportListener _listener;
        private final Message[] _messages;

        private TransportExchange(TransportListener listener, Message... messages)
        {
            super(true);
            _listener=listener;
            _messages = messages;
        }

        @Override
        protected void onRequestCommitted() throws IOException
        {
            _listener.onSending(_messages);
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            if (getResponseStatus() == 200)
            {
                String content=getResponseContent();
                if (content!=null && content.length()>0)
                {
                    List<Message.Mutable> messages = toMessages(getResponseContent());
                    _listener.onMessages(messages);
                }
                else
                    _listener.onProtocolError("Empty response: "+this);
            }
            else
            {
                _listener.onProtocolError("Unexpected response "+getResponseStatus()+": "+this);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            _listener.onConnectException(x);
        }

        @Override
        protected void onException(Throwable x)
        {
            _listener.onException(x);
        }

        @Override
        protected void onExpire()
        {
            _listener.onExpire();
        }
    }

    @Override
    public void reset()
    {
    }
}
