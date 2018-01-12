/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.masks.ZeroMasker;
import org.mozilla.javascript.ScriptableObject;

public class WebSocketConnector extends ScriptableObject {
    private org.eclipse.jetty.websocket.client.WebSocketClient wsClient;
    private QueuedThreadPool threadPool;
    private JavaScriptCookieStore cookieStore;

    public WebSocketConnector() {
    }

    public void jsConstructor(JavaScriptCookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    @Override
    public String getClassName() {
        return "WebSocketConnector";
    }

    public void start() throws Exception {
        threadPool = new QueuedThreadPool();
        wsClient = new WebSocketClient();
        wsClient.setExecutor(threadPool);
        wsClient.setMasker(new ZeroMasker());
        wsClient.setCookieStore(cookieStore.getStore());
        wsClient.start();
    }

    public void stop() throws Exception {
        wsClient.stop();
        threadPool.stop();
    }

    public WebSocketClient getWebSocketClient() {
        return wsClient;
    }
}
