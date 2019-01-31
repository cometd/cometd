/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.common.TransportException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class LongPollingTransport extends HttpClientTransport {
    public static final String NAME = "long-polling";
    public static final String PREFIX = "long-polling.json";

    private final HttpClient _httpClient;
    private final List<Request> _requests = new ArrayList<>();
    private volatile boolean _aborted;
    private volatile int _maxMessageSize;
    private volatile boolean _appendMessageType;
    private volatile CookieManager _cookieManager;
    private volatile Map<String, Object> _advice;

    public LongPollingTransport(Map<String, Object> options, HttpClient httpClient) {
        this(null, options, httpClient);
    }

    public LongPollingTransport(String url, Map<String, Object> options, HttpClient httpClient) {
        super(NAME, url, options);
        _httpClient = httpClient;
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(String bayeuxVersion) {
        return true;
    }

    @Override
    public void init() {
        super.init();

        _aborted = false;

        long defaultMaxNetworkDelay = _httpClient.getIdleTimeout();
        if (defaultMaxNetworkDelay <= 0) {
            defaultMaxNetworkDelay = 10000;
        }
        setMaxNetworkDelay(defaultMaxNetworkDelay);

        _maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, 1024 * 1024);

        Pattern uriRegexp = Pattern.compile("(^https?://(((\\[[^\\]]+\\])|([^:/\\?#]+))(:(\\d+))?))?([^\\?#]*)(.*)?");
        Matcher uriMatcher = uriRegexp.matcher(getURL());
        if (uriMatcher.matches()) {
            String afterPath = uriMatcher.group(9);
            _appendMessageType = afterPath == null || afterPath.trim().length() == 0;
        }
        _cookieManager = new CookieManager(getCookieStore(), CookiePolicy.ACCEPT_ALL);
    }

    @Override
    public void abort() {
        List<Request> requests = new ArrayList<>();
        synchronized (this) {
            _aborted = true;
            requests.addAll(_requests);
            _requests.clear();
        }
        for (Request request : requests) {
            request.abort(new Exception("Transport " + this + " aborted"));
        }
    }

    @Override
    public void send(final TransportListener listener, final List<Message.Mutable> messages) {
        String url = getURL();
        final URI uri = URI.create(url);
        if (_appendMessageType && messages.size() == 1) {
            Message.Mutable message = messages.get(0);
            if (message.isMeta()) {
                String type = message.getChannel().substring(Channel.META.length());
                if (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                url += type;
            }
        }

        final Request request = _httpClient.newRequest(url).method(HttpMethod.POST);
        request.header(HttpHeader.CONTENT_TYPE.asString(), "application/json;charset=UTF-8");

        List<HttpCookie> cookies = getCookieStore().get(uri);
        StringBuilder value = new StringBuilder(cookies.size() * 32);
        for (HttpCookie cookie : cookies) {
            if (value.length() > 0) {
                value.append("; ");
            }
            value.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        request.header(HttpHeader.COOKIE.asString(), value.toString());

        request.content(new StringContentProvider(generateJSON(messages)));

        synchronized (this) {
            if (_aborted) {
                throw new IllegalStateException("Aborted");
            }
            _requests.add(request);
        }

        customize(request, Promise.from(
                customizedRequest -> sendAfterCustomize(listener, messages, uri, customizedRequest),
                error -> listener.onFailure(error, messages)
        ));
    }

    private void sendAfterCustomize(TransportListener listener,
                                    List<Message.Mutable> messages,
                                    URI uri,
                                    Request customizedRequest) {
        customizedRequest.listener(new Request.Listener.Adapter() {
            @Override
            public void onHeaders(Request request) {
                listener.onSending(messages);
            }
        });

        long maxNetworkDelay = getMaxNetworkDelay();
        if (messages.size() == 1) {
            Message.Mutable message = messages.get(0);
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                Map<String, Object> advice = message.getAdvice();
                if (advice == null) {
                    advice = _advice;
                }
                if (advice != null) {
                    Object timeout = advice.get("timeout");
                    if (timeout instanceof Number) {
                        maxNetworkDelay += ((Number) timeout).longValue();
                    } else if (timeout != null) {
                        maxNetworkDelay += Long.parseLong(timeout.toString());
                    }
                }
            }
        }
        // Set the idle timeout for this request larger than the total timeout
        // so there are no races between the two timeouts
        customizedRequest.idleTimeout(maxNetworkDelay * 2, TimeUnit.MILLISECONDS);
        customizedRequest.timeout(maxNetworkDelay, TimeUnit.MILLISECONDS);
        customizedRequest.send(new BufferingResponseListener(_maxMessageSize) {
            @Override
            public boolean onHeader(Response response, HttpField field) {
                HttpHeader header = field.getHeader();
                if (header != null && (header == HttpHeader.SET_COOKIE || header == HttpHeader.SET_COOKIE2)) {
                    // We do not allow cookies to be handled by HttpClient, since one
                    // HttpClient instance is shared by multiple BayeuxClient instances.
                    // Instead, we store the cookies in the BayeuxClient instance.
                    Map<String, List<String>> cookies = new HashMap<>(1);
                    cookies.put(field.getName(), Collections.singletonList(field.getValue()));
                    storeCookies(uri, cookies);
                    return false;
                }
                return true;
            }

            private void storeCookies(URI uri, Map<String, List<String>> cookies) {
                try {
                    _cookieManager.put(uri, cookies);
                } catch (IOException x) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("", x);
                    }
                }
            }

            @Override
            public void onComplete(Result result) {
                synchronized (LongPollingTransport.this) {
                    _requests.remove(result.getRequest());
                }

                if (result.isFailed()) {
                    listener.onFailure(result.getFailure(), messages);
                    return;
                }

                Response response = result.getResponse();
                int status = response.getStatus();
                if (status == HttpStatus.OK_200) {
                    String content = getContentAsString();
                    if (content != null && content.length() > 0) {
                        try {
                            List<Message.Mutable> messages = parseMessages(content);
                            if (logger.isDebugEnabled()) {
                                logger.debug("Received messages {}", messages);
                            }
                            for (Message.Mutable message : messages) {
                                if (message.isSuccessful() && Channel.META_CONNECT.equals(message.getChannel())) {
                                    Map<String, Object> advice = message.getAdvice();
                                    if (advice != null && advice.get("timeout") != null) {
                                        _advice = advice;
                                    }
                                }
                            }
                            listener.onMessages(messages);
                        } catch (ParseException x) {
                            listener.onFailure(x, messages);
                        }
                    } else {
                        Map<String, Object> failure = new HashMap<>(2);
                        // Convert the 200 into 204 (no content)
                        failure.put("httpCode", 204);
                        TransportException x = new TransportException(failure);
                        listener.onFailure(x, messages);
                    }
                } else {
                    Map<String, Object> failure = new HashMap<>(2);
                    failure.put("httpCode", status);
                    TransportException x = new TransportException(failure);
                    listener.onFailure(x, messages);
                }
            }
        });
    }

    protected void customize(Request request) {
    }

    protected void customize(Request request, Promise<Request> promise) {
        customize(request);
        promise.succeed(request);
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final HttpClient httpClient;

        public Factory(HttpClient httpClient) {
            this.httpClient = httpClient;
            addBean(httpClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            return new LongPollingTransport(url, options, httpClient);
        }
    }
}
