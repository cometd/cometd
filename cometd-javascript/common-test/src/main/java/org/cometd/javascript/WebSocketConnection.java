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

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketConnection extends ScriptableObject implements WebSocketListener
{
    private final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private ThreadModel threads;
    private Scriptable thiz;
    private org.eclipse.jetty.websocket.api.WebSocketConnection connection;

    public WebSocketConnection()
    {
    }

    public void jsConstructor(Object cookieStore, Object threadModel, Scriptable thiz, Object connector, String url)
    {
        this.threads = (ThreadModel)threadModel;
        this.thiz = thiz;
        WebSocketClient wsClient = ((WebSocketConnector)connector).getWebSocketClient();
        try
        {
            URI uri = new URI(url);
            // TODO: pass in cookies
//            wsClient.getUpgradeRequest().setCookieStore();
//            wsClient.getCookies().putAll(((HttpCookieStore)cookieStore).getAll(uri));
            log("Opening WebSocket connection to {}", uri);
            wsClient.connect(this, uri).get();
        }
        catch (final Exception x)
        {
            // This method is invoked from JavaScript, so we must fail asynchronously
            wsClient.getExecutor().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    onWebSocketException(new WebSocketException(x));
                }
            });
        }
    }

    public String getClassName()
    {
        return "WebSocketConnection";
    }

    public void jsFunction_send(String data) throws IOException
    {
        log("WebSocket sending data {}", data);
        connection.write(data);
    }

    public void jsFunction_close(int code, String reason) throws IOException
    {
        connection.close(code, reason);
    }

    @Override
    public void onWebSocketConnect(org.eclipse.jetty.websocket.api.WebSocketConnection connection)
    {
        this.connection = connection;
        log("WebSocket opened connection {}", connection);
        threads.invoke(false, thiz, thiz, "onopen");
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
    }

    @Override
    public void onWebSocketText(String data)
    {
        log("WebSocket message data {}", data);
        // Use single quotes so they do not mess up with quotes in the data string
        Object event = threads.evaluate("event", "({data:'" + data + "'})");
        threads.invoke(false, thiz, thiz, "onmessage", event);
    }

    @Override
    public void onWebSocketClose(int closeCode, String reason)
    {
        log("WebSocket closed with code {}/{}", closeCode, reason);
        // Use single quotes so they do not mess up with quotes in the reason string
        Object event = threads.evaluate("event", "({code:" + closeCode +",reason:'" + reason + "'})");
        threads.invoke(false, thiz, thiz, "onclose", event);
    }

    @Override
    public void onWebSocketException(WebSocketException x)
    {
        log("WebSocket exception {}", x);
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
