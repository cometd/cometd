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
package org.cometd.client.http.okhttp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

public class OkHttpClientTransport extends AbstractHttpClientTransport {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json;charset=UTF-8");

    private final OkHttpClient _client;
    private List<Call> _calls = new ArrayList<>();

    public OkHttpClientTransport(Map<String, Object> options, OkHttpClient client) {
        this(null, options, client);
    }
    public OkHttpClientTransport(String url, Map<String, Object> options, OkHttpClient client) {
        super(url, options);
        _client = client.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .addInterceptor(new SendingInterceptor())
                .build();
    }

    protected OkHttpClient getOkHttpClient() {
        return _client;
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
        synchronized (this) {
            super.abort(failure);
            requests = new ArrayList<>(_calls);
            _calls.clear();
        }
        requests.forEach(Call::cancel);
    }

    @Override
    public void send(TransportListener listener, List<Message.Mutable> messages) {
        Request.Builder request = new Request.Builder()
                .url(newRequestURI(messages))
                .post(RequestBody.create(JSON_MEDIA_TYPE, generateJSON(messages)));

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
    }

    protected void customize(Request.Builder request, Promise<Request.Builder> promise) {
        promise.succeed(request);
    }

    private void send(TransportListener listener, List<Message.Mutable> messages, URI cookieURI, Request.Builder request) {
        long maxNetworkDelay = calculateMaxNetworkDelay(messages);
        OkHttpClient client = _client.newBuilder()
                // Set the read timeout for this request larger than the total
                // timeout so there are no races between the two timeouts.
                .callTimeout(maxNetworkDelay, TimeUnit.MILLISECONDS)
                .readTimeout(2 * maxNetworkDelay, TimeUnit.MILLISECONDS)
                .build();

        request = request.tag(TransportListener.class, listener).tag(List.class, messages);

        Call call = client.newCall(request.build());
        synchronized (this) {
            if (!isAborted()) {
                _calls.add(call);
            }
        }

        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                if (code == 200) {
                    storeCookies(cookieURI, response.headers().toMultimap());
                    // Blocking I/O, unfortunately.
                    try (ResponseBody body = response.body()) {
                        processResponseContent(listener, messages, body.string());
                    }
                } else {
                    processWrongResponseCode(listener, messages, code);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                listener.onFailure(e, messages);
            }
        });
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final OkHttpClient _client;

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
            @SuppressWarnings("unchecked")
            List<Message.Mutable> messages = request.tag(List.class);
            listener.onSending(messages);
            return chain.proceed(request);
        }
    }
}
