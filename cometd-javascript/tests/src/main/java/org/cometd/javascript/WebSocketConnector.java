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
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class WebSocketConnector {
    private final XMLHttpRequestClient xhrClient;
    private WebSocketClient wsClient;

    public WebSocketConnector(XMLHttpRequestClient xhrClient) {
        this.xhrClient = xhrClient;
    }

    public void start() throws Exception {
        HttpClient httpClient = xhrClient.getHttpClient();
        wsClient = new WebSocketClient(httpClient);
        wsClient.start();
    }

    public void stop() throws Exception {
        wsClient.stop();
    }

    public WebSocketClient getWebSocketClient() {
        return wsClient;
    }
}
