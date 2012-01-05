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
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketClient extends ScriptableObject implements WebSocket.OnTextMessage
{
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
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
            log("Opening WebSocket connection to {}", uri);
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
        log("WebSocket sending data {}", data);
        connection.sendMessage(data);
    }

    public void jsFunction_close(int code, String reason)
    {
        connection.close(code, reason);
    }

    public void onOpen(Connection connection)
    {
        this.connection = (FrameConnection)connection;
        log("WebSocket opened connection {}", connection);
        threads.invoke(false, thiz, thiz, "onopen");
    }

    public void onMessage(String data)
    {
        log("WebSocket message data {}", data);
        // Use single quotes so they do not mess up with quotes in the data string
        Object event = threads.evaluate("event", "({data:'" + data + "'})");
        threads.invoke(false, thiz, thiz, "onmessage", event);
    }

    public void onClose(int closeCode, String reason)
    {
        log("WebSocket closed with code {}/{}", closeCode, reason);
        // Use single quotes so they do not mess up with quotes in the reason string
        Object event = threads.evaluate("event", "({code:" + closeCode +",reason:'" + reason + "'})");
        threads.invoke(false, thiz, thiz, "onclose", event);
    }

    public void onError(Throwable x)
    {
        log("WebSocket error {}", x);
        threads.invoke(false, thiz, thiz, "onerror");
    }

    private void log(String message, Object... args)
    {
        if (Boolean.getBoolean("debugTests"))
            logger.info(message, args);
        else
            logger.debug(message, args);
    }
}
