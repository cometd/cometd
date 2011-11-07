/*
 * Copyright (c) 2011 the original author or authors.
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

import java.io.IOException;
import java.net.URI;

import org.eclipse.jetty.websocket.WebSocket;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

public class WebSocketClient extends ScriptableObject implements WebSocket.OnTextMessage
{
    private ThreadModel threads;
    private Scriptable thiz;
    private FrameConnection connection;

    public WebSocketClient()
    {
    }

    public WebSocketClient(Object cookieStore, Object threadModel, Scriptable thiz, Object factory, String url)
    {
        this.threads = (ThreadModel)threadModel;
        this.thiz = thiz;
        try
        {
            WebSocketClientFactory wsFactory = (WebSocketClientFactory)factory;
            org.eclipse.jetty.websocket.WebSocketClient wsClient = wsFactory.newWebSocketClient();
            URI uri = new URI(url);
            wsClient.getCookies().putAll(((HttpCookieStore)cookieStore).getAll(uri));
            wsClient.open(uri, this);
        }
        catch (Exception x)
        {
            onError(x);
        }
    }

    public String getClassName()
    {
        return "WebSocketClient";
    }

    public void jsFunction_send(String data) throws IOException
    {
        connection.sendMessage(data);
    }

    public void jsFunction_close(int code, String reason)
    {
        connection.close(code, reason);
    }

    public void onOpen(Connection connection)
    {
        this.connection = (FrameConnection)connection;
        Object onOpen = ScriptableObject.getProperty(thiz, "onopen");
        if (onOpen instanceof Function)
            threads.execute(thiz, thiz, (Function)onOpen);
    }

    public void onMessage(String data)
    {
        Object onMessage = ScriptableObject.getProperty(thiz, "onmessage");
        if (onMessage instanceof Function)
        {
            // Use single quotes so they do not mess up with quotes in the data string
            Object event = threads.evaluate("event", "({data:'" + data + "'})");
            threads.execute(thiz, thiz, (Function)onMessage, event);
        }
    }

    public void onClose(int closeCode, String message)
    {
        Object onClose = ScriptableObject.getProperty(thiz, "onclose");
        if (onClose instanceof Function)
            threads.execute(thiz, thiz, (Function)onClose);
    }

    public void onError(Throwable x)
    {
        Object onError = ScriptableObject.getProperty(thiz, "onerror");
        if (onError instanceof Function)
            threads.execute(thiz, thiz, (Function)onError);
    }
}
