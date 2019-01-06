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
package org.cometd.javascript;

import org.eclipse.jetty.client.HttpClient;
import org.mozilla.javascript.ScriptableObject;

/**
 * Implementation of the XMLHttpRequest functionality using Jetty's HttpClient.
 */
public class XMLHttpRequestClient extends ScriptableObject {
    private HttpClient httpClient;
    private JavaScriptCookieStore cookieStore;

    public XMLHttpRequestClient() {
    }

    public void jsConstructor(JavaScriptCookieStore cookieStore) throws Exception {
        this.cookieStore = cookieStore;
    }

    @Override
    public String getClassName() {
        return "XMLHttpRequestClient";
    }

    public void start() throws Exception {
        httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerDestination(2);
        httpClient.setIdleTimeout(300000);
        httpClient.setCookieStore(cookieStore.getStore());
        httpClient.start();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void stop() throws Exception {
        httpClient.stop();
    }
}
