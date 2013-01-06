/*
 * Copyright (c) 2010 the original author or authors.
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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.ProtocolException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

public class Jetty7LongPollingTransport extends HttpClientTransport
{
    public static final String NAME = "long-polling";
    public static final String PREFIX = "long-polling.json";

    public static Jetty7LongPollingTransport create(Map<String, Object> options)
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(5000);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setMaxConnectionsPerAddress(32768);
        return create(options, httpClient);
    }

    public static Jetty7LongPollingTransport create(Map<String, Object> options, HttpClient httpClient)
    {
        Jetty7LongPollingTransport transport = new Jetty7LongPollingTransport(options, httpClient);
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
    private final List<TransportExchange> _exchanges = new ArrayList<>();
    private volatile boolean _aborted;
    private volatile long _maxNetworkDelay;
    private volatile boolean _appendMessageType;
    private volatile CookieManager _cookieManager;
    private volatile Map<String, Object> _advice;

    public Jetty7LongPollingTransport(Map<String, Object> options, HttpClient httpClient)
    {
        super(NAME, options);
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
        _cookieManager = new CookieManager(getCookieStore(), CookiePolicy.ACCEPT_ALL);
    }

    @Override
    public void abort()
    {
        List<TransportExchange> exchanges = new ArrayList<>();
        synchronized (this)
        {
            _aborted = true;
            exchanges.addAll(_exchanges);
            _exchanges.clear();
        }
        for (TransportExchange exchange : exchanges)
        {
            exchange.cancel();
            exchange._listener.onFailure(new IOException("Aborted"), exchange._messages);
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
            listener.onFailure(x, messages);
        }
    }

    protected void customize(ContentExchange exchange)
    {
        StringBuilder builder = new StringBuilder();
        for (HttpCookie cookie : getCookieStore().get(URI.create(getURL())))
        {
            builder.setLength(0);
            builder.append(cookie.getName()).append("=").append(cookie.getValue());
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
            if (headerName == HttpHeaders.SET_COOKIE_ORDINAL ||
                    headerName == HttpHeaders.SET_COOKIE2_ORDINAL)
            {
                Map<String, List<String>> cookies = new HashMap<>(1);
                cookies.put(name.toString("ISO-8859-1"), Collections.singletonList(value.toString("UTF-8")));
                storeCookies(URI.create(getURL()), cookies);
            }
        }

        private void storeCookies(URI uri, Map<String, List<String>> cookies)
        {
            try
            {
                _cookieManager.put(uri, cookies);
            }
            catch (IOException x)
            {
                logger.debug("", x);
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
                    _listener.onFailure(new ProtocolException("Empty response: " + this), _messages);
            }
            else
            {
                _listener.onFailure(new ProtocolException("Unexpected response " + getResponseStatus() + ": " + this), _messages);
            }
        }

        @Override
        protected void onConnectionFailed(Throwable x)
        {
            if (complete())
                _listener.onFailure(x, _messages);
        }

        @Override
        protected void onException(Throwable x)
        {
            if (complete())
                _listener.onFailure(x, _messages);
        }

        @Override
        protected void onExpire()
        {
            if (complete())
                _listener.onFailure(new TimeoutException(), _messages);
        }

        private boolean complete()
        {
            synchronized (Jetty7LongPollingTransport.this)
            {
                return _exchanges.remove(this);
            }
        }
    }
}
