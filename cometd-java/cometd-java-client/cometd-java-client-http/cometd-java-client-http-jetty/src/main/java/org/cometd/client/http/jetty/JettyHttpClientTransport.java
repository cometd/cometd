/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.client.http.jetty;

import java.net.HttpCookie;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.client.http.common.AbstractHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.BufferingJSONAsyncParser;
import org.cometd.common.JSONContext;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyHttpClientTransport extends AbstractHttpClientTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(JettyHttpClientTransport.class);

    private final List<Request> _requests = new ArrayList<>();
    private final HttpClient _httpClient;

    public JettyHttpClientTransport(Map<String, Object> options, HttpClient httpClient) {
        this(null, options, httpClient);
    }

    public JettyHttpClientTransport(String url, Map<String, Object> options, HttpClient httpClient) {
        super(url, options);
        _httpClient = httpClient;
    }

    protected HttpClient getHttpClient() {
        return _httpClient;
    }

    @Override
    public void init() {
        super.init();
        long defaultMaxNetworkDelay = getHttpClient().getIdleTimeout();
        if (defaultMaxNetworkDelay <= 0) {
            defaultMaxNetworkDelay = 10000;
        }
        setMaxNetworkDelay(defaultMaxNetworkDelay);
    }

    @Override
    public void abort(Throwable failure) {
        List<Request> requests;
        synchronized (this) {
            super.abort(failure);
            requests = new ArrayList<>(_requests);
            _requests.clear();
        }
        for (Request request : requests) {
            request.abort(failure);
        }
    }

    @Override
    public void send(final TransportListener listener, final List<Message.Mutable> messages) {
        String requestURI = newRequestURI(messages);

        final Request request = _httpClient.newRequest(requestURI).method(HttpMethod.POST);
        request.header(HttpHeader.CONTENT_TYPE.asString(), "application/json;charset=UTF-8");

        URI cookieURI = URI.create(getURL());
        List<HttpCookie> cookies = getCookies(cookieURI);
        StringBuilder value = new StringBuilder(cookies.size() * 32);
        for (HttpCookie cookie : cookies) {
            if (value.length() > 0) {
                value.append("; ");
            }
            value.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        request.header(HttpHeader.COOKIE.asString(), value.toString());

        request.content(new StringContentProvider(generateJSON(messages)));

        customize(request, Promise.from(
                customizedRequest -> send(listener, messages, cookieURI, customizedRequest),
                error -> listener.onFailure(error, messages)
        ));
    }

    private void send(TransportListener listener, List<Message.Mutable> messages, URI cookieURI, Request request) {
        request.listener(new Request.Listener.Adapter() {
            @Override
            public void onHeaders(Request request) {
                listener.onSending(messages);
            }
        });

        long maxNetworkDelay = calculateMaxNetworkDelay(messages);
        // Disable the idle timeout.
        request.idleTimeout(0, TimeUnit.MILLISECONDS);
        // Schedule a task to timeout the request.
        AtomicReference<ScheduledFuture<?>> timeoutTaskRef = new AtomicReference<>();
        ScheduledFuture<?> newTask = getScheduler().schedule(() -> onTimeout(listener, messages, request, maxNetworkDelay, timeoutTaskRef), maxNetworkDelay, TimeUnit.MILLISECONDS);
        timeoutTaskRef.set(newTask);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Started waiting for message replies, {} ms, task@{}", maxNetworkDelay, Integer.toHexString(newTask.hashCode()));
        }

        request.onComplete(result -> {
            ScheduledFuture<?> task = timeoutTaskRef.get();
            task.cancel(false);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Cancelled waiting for message replies, task@{}", Integer.toHexString(task.hashCode()));
            }
        });

        synchronized (this) {
            if (!isAborted()) {
                _requests.add(request);
            }
        }

        request.send(new ResponseListener(listener, messages, cookieURI));
    }

    private void onTimeout(TransportListener listener, List<? extends Message> messages, Request request, long delay, AtomicReference<ScheduledFuture<?>> timeoutTaskRef) {
        listener.onTimeout(messages, Promise.from(result -> {
            if (result > 0) {
                ScheduledFuture<?> newTask = getScheduler().schedule(() -> onTimeout(listener, messages, request, delay + result, timeoutTaskRef), result, TimeUnit.MILLISECONDS);
                ScheduledFuture<?> oldTask = timeoutTaskRef.getAndSet(newTask);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Extended waiting for message replies, {} ms, oldTask@{}, newTask@{}", result, Integer.toHexString(oldTask.hashCode()), Integer.toHexString(newTask.hashCode()));
                }
            } else {
                request.abort(new TimeoutException("Network delay expired: " + delay + " ms"));
            }
        }, request::abort));
    }

    protected void customize(Request request) {
    }

    protected void customize(Request request, Promise<Request> promise) {
        try {
            customize(request);
            promise.succeed(request);
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final HttpClient httpClient;

        public Factory(HttpClient httpClient) {
            this.httpClient = httpClient;
            addBean(httpClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            return new JettyHttpClientTransport(url, options, httpClient);
        }
    }

    private class ResponseListener implements Response.Listener {
        private final TransportListener listener;
        private final List<Message.Mutable> outgoing;
        private final URI cookieURI;
        private long contentLength;
        private JSONContext.AsyncParser parser;

        private ResponseListener(TransportListener listener, List<Message.Mutable> messages, URI cookieURI) {
            this.listener = listener;
            this.outgoing = messages;
            this.cookieURI = cookieURI;
        }

        @Override
        public boolean onHeader(Response response, HttpField field) {
            if (response.getStatus() == HttpStatus.OK_200) {
                HttpHeader header = field.getHeader();
                if (header == HttpHeader.SET_COOKIE || header == HttpHeader.SET_COOKIE2) {
                    // We do not allow cookies to be handled by HttpClient, since one
                    // HttpClient instance is shared by multiple BayeuxClient instances.
                    // Instead, we store the cookies in the BayeuxClient instance.
                    Map<String, List<String>> cookies = new HashMap<>(1);
                    cookies.put(field.getName(), Collections.singletonList(field.getValue()));
                    storeCookies(cookieURI, cookies);
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onHeaders(Response response) {
            if (response.getStatus() == HttpStatus.OK_200) {
                JSONContext.Client jsonContext = getJSONContextClient();
                parser = jsonContext.newAsyncParser();
                if (parser == null) {
                    parser = new BufferingJSONAsyncParser(jsonContext);
                }
            }
        }

        @Override
        public void onContent(Response response, ByteBuffer content) {
            if (response.getStatus() == HttpStatus.OK_200) {
                contentLength += content.remaining();
                int maxLength = getMaxMessageSize();
                if (maxLength > 0 && contentLength > maxLength) {
                    response.abort(new IllegalArgumentException("Buffering capacity " + maxLength + " exceeded"));
                } else {
                    parse(response, content);
                }
            }
        }

        @Override
        public void onComplete(Result result) {
            synchronized (JettyHttpClientTransport.this) {
                _requests.remove(result.getRequest());
            }

            if (result.isFailed()) {
                listener.onFailure(result.getFailure(), outgoing);
                return;
            }

            try {
                Response response = result.getResponse();
                int status = response.getStatus();
                if (status == HttpStatus.OK_200) {
                    List<Message.Mutable> incoming = parser.complete();
                    processResponseMessages(listener, incoming);
                } else {
                    processWrongResponseCode(listener, outgoing, status);
                }
            } catch (Throwable x) {
                listener.onFailure(x, outgoing);
            }
        }

        private void parse(Response response, ByteBuffer content) {
            try {
                parser.parse(content);
            } catch (Throwable x) {
                response.abort(x);
            }
        }
    }
}
