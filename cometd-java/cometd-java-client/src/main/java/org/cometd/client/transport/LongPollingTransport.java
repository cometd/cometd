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

import java.net.ProtocolException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;

public class LongPollingTransport extends HttpClientTransport
{
    public static final String NAME = "long-polling";
    public static final String PREFIX = "long-polling.json";

    public static LongPollingTransport create(Map<String, Object> options)
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(5000);
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
    private final List<Request> _requests = new ArrayList<>();
    private volatile boolean _aborted;
    private volatile long _maxNetworkDelay;
    private volatile boolean _appendMessageType;
    private volatile Map<String, Object> _advice;

    public LongPollingTransport(Map<String, Object> options, HttpClient httpClient)
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
        _maxNetworkDelay = getOption(MAX_NETWORK_DELAY_OPTION, _httpClient.getIdleTimeout());
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
        List<Request> requests = new ArrayList<>();
        synchronized (this)
        {
            _aborted = true;
            requests.addAll(_requests);
            _requests.clear();
        }
        for (Request request : requests)
        {
            request.abort();
        }
    }

    @Override
    public void send(final TransportListener listener, final Message.Mutable... messages)
    {
        String url = getURL();
        if (_appendMessageType && messages.length == 1 && messages[0].isMeta())
        {
            String type = messages[0].getChannel().substring(Channel.META.length());
            if (url.endsWith("/"))
                url = url.substring(0, url.length() - 1);
            url += type;
        }

        final Request request = _httpClient.newRequest(url).method(HttpMethod.POST);
        request.header(HttpHeader.CONTENT_TYPE.asString(), "application/json;charset=UTF-8");
        request.content(new StringContentProvider(generateJSON(messages)));

        synchronized (this)
        {
            if (_aborted)
                throw new IllegalStateException("Aborted");
            _requests.add(request);
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
        request.idleTimeout(maxNetworkDelay);

        request.listener(new Request.Listener.Empty()
        {
            @Override
            public void onHeaders(Request request)
            {
                listener.onSending(messages);
            }
        });

        request.send(new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                synchronized (LongPollingTransport.this)
                {
                    _requests.remove(result.getRequest());
                }

                if (result.isFailed())
                {
                    listener.onFailure(result.getFailure(), messages);
                    return;
                }

                Response response = result.getResponse();
                int status = response.status();
                if (status == HttpStatus.OK_200)
                {
                    String content = getContentAsString();
                    if (content != null && content.length() > 0)
                    {
                        try
                        {
                            List<Message.Mutable> messages = parseMessages(content);
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
                            listener.onMessages(messages);
                        }
                        catch (ParseException x)
                        {
                            listener.onFailure(x, messages);
                        }
                    }
                    else
                    {
                        listener.onFailure(new ProtocolException("Empty response content " + request), messages);
                    }
                }
                else
                {
                    listener.onFailure(new ProtocolException("Unexpected response " + status + ": " + request), messages);
                }
            }
        });
    }
}
