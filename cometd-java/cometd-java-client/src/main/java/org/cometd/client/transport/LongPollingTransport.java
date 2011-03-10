package org.cometd.client.transport;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class LongPollingTransport extends HttpClientTransport
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
    private final List<HttpExchange> _exchanges = new ArrayList<HttpExchange>();
    private volatile boolean _aborted;
    private volatile boolean _appendMessageType;
    private volatile Map<String, Object> _advice;

    public LongPollingTransport(Map<String, Object> options, HttpClient httpClient)
    {
        super("long-polling", options);
        _httpClient = httpClient;
        setOptionPrefix("long-polling.json");
    }

    public boolean accept(String bayeuxVersion)
    {
        return true;
    }

    @Override
    public void init()
    {
        super.init();
        _aborted = false;
        Pattern uriRegexp = Pattern.compile("(^https?://(([^:/\\?#]+)(:(\\d+))?))?([^\\?#]*)(.*)?");
        Matcher uriMatcher = uriRegexp.matcher(getURL());
        if (uriMatcher.matches())
        {
            String afterPath = uriMatcher.group(7);
            _appendMessageType = afterPath == null || afterPath.trim().length() == 0;
        }
    }

    @Override
    public void abort()
    {
        synchronized (this)
        {
            _aborted = true;
            for (HttpExchange exchange : _exchanges)
                exchange.cancel();
        }
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void send(final TransportListener listener, Message.Mutable... messages)
    {
        TransportExchange httpExchange = new TransportExchange(listener, messages);
        httpExchange.setMethod("POST");

        String url = getURL();
        httpExchange.setURL(url);
        if (_appendMessageType && messages.length == 1 && messages[0].isMeta())
        {
            String type = messages[0].getChannel().substring(Channel.META.length());
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
            customize(httpExchange);

            synchronized (this)
            {
                if (_aborted)
                    throw new IllegalStateException("Aborted");
                _exchanges.add(httpExchange);
            }

            long maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, _httpClient.getTimeout());
            if (messages.length == 1 && Channel.META_CONNECT.equals(messages[0].getChannel()))
            {
                Map<String, Object> advice = messages[0].getAdvice();
                if (advice == null)
                    advice = _advice;
                if (advice != null)
                {
                    Object timeout = advice.get("timeout");
                    if (timeout instanceof Number)
                        maxNetworkDelay += ((Number)timeout).longValue();
                    else if (timeout != null)
                        maxNetworkDelay += Long.parseLong(timeout.toString());
                }
            }
            httpExchange.setTimeout(maxNetworkDelay);

            _httpClient.send(httpExchange);
        }
        catch (Exception x)
        {
            listener.onException(x, messages);
        }
    }

    protected void customize(ContentExchange exchange)
    {
        CookieProvider cookieProvider = getCookieProvider();
        if (cookieProvider != null)
        {
            StringBuilder builder = new StringBuilder();
            for (Cookie cookie : cookieProvider.getCookies())
            {
                if (builder.length() > 0)
                    builder.append("; ");
                builder.append(cookie.asString());
            }
            if (builder.length() > 0)
                exchange.setRequestHeader(HttpHeaders.COOKIE, builder.toString());
        }
    }

    private class TransportExchange extends ContentExchange
    {
        private final TransportListener _listener;
        private final Message[] _messages;

        private TransportExchange(TransportListener listener, Message... messages)
        {
            super(true);
            _listener = listener;
            _messages = messages;
        }

        @Override
        protected void onRequestCommitted() throws IOException
        {
            _listener.onSending(_messages);
        }

        @Override
        protected void onResponseHeader(Buffer name, Buffer value) throws IOException
        {
            super.onResponseHeader(name, value);
            int headerName = HttpHeaders.CACHE.getOrdinal(name);
            if (headerName == HttpHeaders.SET_COOKIE_ORDINAL)
            {
                QuotedStringTokenizer tokenizer = new QuotedStringTokenizer(value.toString(), "=;", false, false);
                tokenizer.setSingle(false);

                String cookieName = null;
                if (tokenizer.hasMoreTokens())
                    cookieName = tokenizer.nextToken();

                String cookieValue = null;
                if (tokenizer.hasMoreTokens())
                    cookieValue = tokenizer.nextToken();

                if (cookieName != null && cookieValue != null)
                {
                    int version = 0;
                    String comment = null;
                    String path = null;
                    String domain = null;
                    int maxAge = -1;
                    boolean secure = false;

                    while (tokenizer.hasMoreTokens())
                    {
                        String token = tokenizer.nextToken();
                        if ("Version".equalsIgnoreCase(token))
                            version = Integer.parseInt(tokenizer.nextToken());
                        else if ("Comment".equalsIgnoreCase(token))
                            comment = tokenizer.nextToken();
                        else if ("Path".equalsIgnoreCase(token))
                            path = tokenizer.nextToken();
                        else if ("Domain".equalsIgnoreCase(token))
                            domain = tokenizer.nextToken();
                        else if ("Expires".equalsIgnoreCase(token))
                        {
                            try
                            {
                                Date date = new SimpleDateFormat("EEE, dd-MMM-yy HH:mm:ss 'GMT'").parse(tokenizer.nextToken());
                                Long maxAgeValue = TimeUnit.MILLISECONDS.toSeconds(date.getTime() - System.currentTimeMillis());
                                maxAge = maxAgeValue > 0 ? maxAgeValue.intValue() : 0;
                            }
                            catch (ParseException ignored)
                            {
                            }
                        }
                        else if ("Max-Age".equalsIgnoreCase(token))
                        {
                            try
                            {
                                maxAge = Integer.parseInt(tokenizer.nextToken());
                            }
                            catch (NumberFormatException ignored)
                            {
                            }
                        }
                        else if ("Secure".equalsIgnoreCase(token))
                            secure = true;
                    }

                    Cookie cookie = new Cookie(cookieName, cookieValue, domain, path, maxAge, secure, version, comment);
                    setCookie(cookie);
                }
            }
        }

        @Override
        protected void onResponseComplete() throws IOException
        {
            complete();
            if (getResponseStatus() == 200)
            {
                String content = getResponseContent();
                if (content != null && content.length() > 0)
                {
                    List<Message.Mutable> messages = parseMessages(getResponseContent());
                    for (Message.Mutable message : messages)
                    {
                        if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel()))
                        {
                            Map<String, Object> advice = message.getAdvice();
                            if (advice != null && advice.get("timeout") != null)
                                _advice = advice;
                        }
                    }
                    _listener.onMessages(messages);
                }
                else
                    _listener.onProtocolError("Empty response: " + this, _messages);
            }
            else
            {
                _listener.onProtocolError("Unexpected response " + getResponseStatus() + ": " + this, _messages);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            complete();
            _listener.onConnectException(x, _messages);
        }

        @Override
        protected void onException(Throwable x)
        {
            complete();
            _listener.onException(x, _messages);
        }

        @Override
        protected void onExpire()
        {
            complete();
            _listener.onExpire(_messages);
        }

        private void complete()
        {
            synchronized (LongPollingTransport.this)
            {
                _exchanges.remove(this);
            }
        }
    }
}
