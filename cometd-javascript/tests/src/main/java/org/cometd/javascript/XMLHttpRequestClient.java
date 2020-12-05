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
package org.cometd.javascript;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Implementation of the XMLHttpRequest functionality using Jetty's HttpClient.
 */
public class XMLHttpRequestClient {
    private final JavaScriptCookieStore cookieStore;
    private HttpClient httpClient;

    public XMLHttpRequestClient(JavaScriptCookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    public void start() throws Exception {
        httpClient = new HttpClient(new HttpClientTransportOverHTTP(1));
        httpClient.setMaxConnectionsPerDestination(2);
        httpClient.setIdleTimeout(300000);
        httpClient.setCookieStore(cookieStore.getStore());
        httpClient.setExecutor(new PrivilegedExecutor());
        httpClient.start();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void stop() throws Exception {
        httpClient.stop();
    }

    private class PrivilegedExecutor extends QueuedThreadPool {
        private final ThreadFactory factory = Executors.privilegedThreadFactory();

        public PrivilegedExecutor() {
            setName("httpclient");
        }

        @Override
        public Thread newThread(Runnable runnable) {
            return factory.newThread(runnable);
        }
    }
}
