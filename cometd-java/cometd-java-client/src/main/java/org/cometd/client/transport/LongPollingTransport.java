/*
 * Copyright (c) 2008-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.client.transport;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.common.TransportException;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.QuotedStringTokenizer;

public class LongPollingTransport extends HttpClientTransport
{
    public static final String NAME = "long-polling";
    public static final String PREFIX = "long-polling.json";

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
    private final List<TransportExchange> _exchanges = new ArrayList<TransportExchange>();
    private volatile boolean _aborted;
    private volatile long _maxNetworkDelay;
    private volatile boolean _appendMessageType;
    private volatile Map<String, Object> _advice;

    public LongPollingTransport(Map<String, Object> options, HttpClient httpClient)
    {
        this(null, options, httpClient);
    }

    public LongPollingTransport(String url, Map<String, Object> options, HttpClient httpClient)
    {
        super(NAME, url, options);
        _httpClient = httpClient;
        setOptionPrefix(PREFIX);
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
        _maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, _httpClient.getTimeout());
        Pattern uriRegexp = Pattern.compile("(^https?://(((\\[[^\\]]+\\])|([^:/\\?#]+))(:(\\d+))?))?([^\\?#]*)(.*)?");
        Matcher uriMatcher = uriRegexp.matcher(getURL());
        if (uriMatcher.matches())
        {
            String afterPath = uriMatcher.group(9);
            _appendMessageType = afterPath == null || afterPath.trim().length() == 0;
        }
    }

    @Override
    public void abort()
    {
        List<TransportExchange> exchanges = new ArrayList<TransportExchange>();
        synchronized (this)
        {
            _aborted = true;
            exchanges.addAll(_exchanges);
            _exchanges.clear();
        }
        for (TransportExchange exchange : exchanges)
        {
            exchange.cancel();
            exchange._listener.onException(new IOException("Aborted"), exchange._messages);
        }
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

        String content = generateJSON(messages);

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

            long maxNetworkDelay = _maxNetworkDelay;
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
                                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US);
                                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                                Date date = dateFormat.parse(tokenizer.nextToken());
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
            boolean completed = complete();
            if (!completed)
                return;

            if (getResponseStatus() == 200)
            {
                String content = getResponseContent();
                if (content != null && content.length() > 0)
                {
                    try
                    {
                        List<Message.Mutable> messages = parseMessages(getResponseContent());
                        debug("Received messages {}", messages);
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
                    catch (ParseException x)
                    {
                        onException(x);
                    }
                }
                else
                {
                    Map<String, Object> failure = new HashMap<String, Object>(2);
                    // Convert the 200 into 204 (no content)
                    failure.put("httpCode", 204);
                    TransportException x = new TransportException(failure);
                    _listener.onException(x, _messages);
                }
            }
            else
            {
                Map<String, Object> failure = new HashMap<String, Object>(2);
                failure.put("httpCode", getResponseStatus());
                TransportException x = new TransportException(failure);
                _listener.onException(x, _messages);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            if (complete())
                _listener.onConnectException(x, _messages);
        }

        @Override
        protected void onException(Throwable x)
        {
            if (complete())
                _listener.onException(x, _messages);
        }

        @Override
        protected void onExpire()
        {
            if (complete())
                _listener.onExpire(_messages);
        }

        private boolean complete()
        {
            synchronized (LongPollingTransport.this)
            {
                return _exchanges.remove(this);
            }
        }
    }
}
