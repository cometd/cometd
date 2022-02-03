/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.client.http.okhttp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.client.http.common.AbstractHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OkHttpClientTransport extends AbstractHttpClientTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(OkHttpClientTransport.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json;charset=UTF-8");

    private final AutoLock lock = new AutoLock();
    private final List<Call> calls = new ArrayList<>();
    private final OkHttpClient client;

    public OkHttpClientTransport(Map<String, Object> options, OkHttpClient client) {
        this(null, options, client);
    }

    public OkHttpClientTransport(String url, Map<String, Object> options, OkHttpClient client) {
        this(url, options, null, client);
    }

    public OkHttpClientTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler, OkHttpClient client) {
        super(url, options, scheduler);
        this.client = client.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .addInterceptor(new SendingInterceptor())
                .build();
    }

    protected OkHttpClient getOkHttpClient() {
        return client;
    }

    @Override
    public void init() {
        super.init();
        setMaxNetworkDelay(10000);
        // OkHttpClient default values are maxRequestsPerHost=5 and
        // maxRequests=64, both too small. Using Jetty HttpClient defaults.
        Dispatcher dispatcher = getOkHttpClient().dispatcher();
        dispatcher.setMaxRequestsPerHost(64);
        dispatcher.setMaxRequests(Integer.MAX_VALUE);
    }

    @Override
    public void abort(Throwable failure) {
        List<Call> requests;
        try (AutoLock l = lock.lock()) {
            super.abort(failure);
            requests = new ArrayList<>(calls);
            calls.clear();
        }
        requests.forEach(Call::cancel);
    }

    @Override
    public void send(TransportListener listener, List<Message.Mutable> messages) {
        try {
            Request.Builder request = new Request.Builder()
                    .url(newRequestURI(messages))
                    .post(RequestBody.create(generateJSON(messages), JSON_MEDIA_TYPE));

            URI cookieURI = URI.create(getURL());
            String cookies = getCookies(cookieURI).stream()
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining(";"));
            if (!cookies.isEmpty()) {
                request = request.header("Cookie", cookies);
            }

            customize(request, Promise.from(
                    customizedRequest -> send(listener, messages, cookieURI, customizedRequest),
                    failure -> listener.onFailure(failure, messages)));
        } catch (Throwable x) {
            listener.onFailure(x, messages);
        }
    }

    protected void customize(Request.Builder request, Promise<Request.Builder> promise) {
        promise.succeed(request);
    }

    private void send(TransportListener listener, List<Message.Mutable> messages, URI cookieURI, Request.Builder requestBuilder) {
        long maxNetworkDelay = calculateMaxNetworkDelay(messages);
        OkHttpClient client = this.client.newBuilder()
                // Disable the read timeout.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                // Schedule a task to timeout the request.
                .build();

        requestBuilder = requestBuilder
                .tag(TransportListener.class, listener)
                .tag(List.class, messages);

        Request request = requestBuilder.build();
        Call call = client.newCall(request);

        AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>();
        ScheduledExecutorService scheduler = getScheduler();
        if (scheduler != null) {
            ScheduledFuture<?> newTask = scheduler.schedule(() -> onTimeout(listener, messages, call, maxNetworkDelay, timeoutRef), maxNetworkDelay, TimeUnit.MILLISECONDS);
            timeoutRef.set(newTask);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Started waiting for message replies, {} ms, task@{}", maxNetworkDelay, Integer.toHexString(newTask.hashCode()));
            }
        }

        try (AutoLock l = lock.lock()) {
            if (!isAborted()) {
                calls.add(call);
            }
        }

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Sending request {}", request);
        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Received response {}", response);
                try (AutoLock l = lock.lock()) {
                    calls.remove(call);
                }
                int code = response.code();
                if (code == 200) {
                    storeCookies(cookieURI, response.headers().toMultimap());
                    // Blocking I/O, unfortunately.
                    try (ResponseBody body = response.body()) {
                        cancelTimeoutTask(timeoutRef);
                        String content = body == null ? "" : body.string();
                        processResponseContent(listener, messages, content);
                    }
                } else {
                    cancelTimeoutTask(timeoutRef);
                    processWrongResponseCode(listener, messages, code);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Received response failure", e);
                try (AutoLock l = lock.lock()) {
                    calls.remove(call);
                }
                cancelTimeoutTask(timeoutRef);
                listener.onFailure(e, messages);
            }

            private void cancelTimeoutTask(AtomicReference<ScheduledFuture<?>> timeoutRef) {
                ScheduledFuture<?> task = timeoutRef.get();
                if (task != null) {
                    task.cancel(false);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Cancelled request timeout task@{}", Integer.toHexString(task.hashCode()));
                    }
                }
            }
        });
    }

    private void onTimeout(TransportListener listener, List<? extends Message> messages, Call call, long delay, AtomicReference<ScheduledFuture<?>> timeoutRef) {
        try (AutoLock l = lock.lock()) {
            calls.remove(call);
        }
        listener.onTimeout(messages, Promise.from(result -> {
            if (result > 0) {
                ScheduledExecutorService scheduler = getScheduler();
                if (scheduler != null) {
                    ScheduledFuture<?> newTask = scheduler.schedule(() -> onTimeout(listener, messages, call, delay + result, timeoutRef), result, TimeUnit.MILLISECONDS);
                    ScheduledFuture<?> oldTask = timeoutRef.getAndSet(newTask);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Extended waiting for message replies, {} ms, oldTask@{}, newTask@{}", result, Integer.toHexString(oldTask.hashCode()), Integer.toHexString(newTask.hashCode()));
                    }
                }
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Network delay expired: {} ms", delay);
                }
                call.cancel();
            }
        }, x -> call.cancel()));
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final OkHttpClient _client;

        public Factory() {
            this(new OkHttpClient());
        }

        public Factory(OkHttpClient httpClient) {
            _client = httpClient;
            addBean(httpClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            return new OkHttpClientTransport(url, options, _client);
        }
    }

    private static class SendingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            TransportListener listener = request.tag(TransportListener.class);
            if (listener != null) {
                @SuppressWarnings("unchecked")
                List<Message> messages = request.tag(List.class);
                if (messages != null) {
                    listener.onSending(messages);
                }
            }
            return chain.proceed(request);
        }
    }
}
