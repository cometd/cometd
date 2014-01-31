/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.websocket.client;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.UnresolvedAddressException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.TransportListener;
import org.cometd.common.TransportException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class JettyWebSocketTransport extends AbstractWebSocketTransport<Session>
{
    private final Object _target = new CometDWebSocket();
    private final WebSocketClient _webSocketClient;
    private volatile boolean _webSocketSupported = true;
    private volatile boolean _webSocketConnected = false;
    private volatile Session _wsSession;

    public JettyWebSocketTransport(Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketClient webSocketClient)
    {
        this(null, options, scheduler, webSocketClient);
    }

    public JettyWebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketClient webSocketClient)
    {
        super(url, options, scheduler);
        _webSocketClient = webSocketClient;
    }

    @Override
    public boolean accept(String version)
    {
        return _webSocketSupported;
    }

    @Override
    public void init()
    {
        super.init();

        _webSocketClient.setConnectTimeout(getConnectTimeout());
        _webSocketClient.getPolicy().setIdleTimeout(getIdleTimeout());
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketClient.getPolicy().getMaxTextMessageSize());
        _webSocketClient.getPolicy().setMaxTextMessageSize(maxMessageSize);
        _webSocketClient.setCookieStore(getCookieStore());
    }

    protected void disconnect(String reason)
    {
        Session session = _wsSession;
        _wsSession = null;
        if (session != null && session.isOpen())
        {
            logger.debug("Closing websocket session {}", session);
            session.close(1000, reason);
        }
    }

    @Override
    protected void send(Session session, String content, TransportListener listener, List<Mutable> messages)
    {
        try
        {
            session.getRemote().sendStringByFuture(content).get();
        }
        catch (Throwable x)
        {
            if (x instanceof ExecutionException)
                x = x.getCause();
            complete(messages);
            disconnect("Exception");
            listener.onFailure(x, messages);
        }
    }

    protected Session connect(String uri, TransportListener listener, List<Mutable> messages)
    {
        Session session = _wsSession;
        if (session != null)
            return session;

        try
        {
            logger.debug("Opening websocket session to {}", uri);

            session = connect(uri);
            _webSocketConnected = true;

            if (isAborted())
                listener.onFailure(new Exception("Aborted"), messages);

            return _wsSession = session;
        }
        catch (ConnectException | SocketTimeoutException | UnresolvedAddressException x)
        {
            // Cannot connect, assume the server supports WebSocket until proved otherwise
            listener.onFailure(x, messages);
        }
        catch (UpgradeException x)
        {
            _webSocketSupported = false;
            Map<String, Object> failure = new HashMap<>(2);
            failure.put("websocketCode", 1002);
            failure.put("httpCode", x.getResponseStatusCode());
            listener.onFailure(new TransportException(x, failure), messages);
        }
        catch (Throwable x)
        {
            _webSocketSupported = isStickyReconnect() && _webSocketConnected;
            listener.onFailure(x, messages);
        }

        return null;
    }

    protected Session connect(String uri) throws IOException, InterruptedException
    {
        try
        {
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            String protocol = getProtocol();
            if (protocol != null)
                request.setSubProtocols(protocol);
            return _webSocketClient.connect(_target, new URI(uri), request).get();
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            if (cause instanceof IOException)
                throw (IOException)cause;
            throw new IOException(cause);
        }
        catch (URISyntaxException x)
        {
            throw new IOException(x);
        }
    }

    private class CometDWebSocket implements WebSocketListener
    {
        @Override
        public void onWebSocketConnect(Session session)
        {
            _wsSession = session;
            logger.debug("Opened websocket session {}", session);
        }

        @Override
        public void onWebSocketClose(int closeCode, String reason)
        {
            logger.debug("Closed websocket connection with code {} {}: {} ", closeCode, reason, _wsSession);
            _wsSession = null;
            failMessages(new EOFException("Connection closed " + closeCode + " " + reason));
        }

        @Override
        public void onWebSocketText(String data)
        {
            try
            {
                List<Mutable> messages = parseMessages(data);
                logger.debug("Received messages {}", data);
                onMessages(messages);
            }
            catch (ParseException x)
            {
                failMessages(x);
                disconnect("Exception");
            }
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        @Override
        public void onWebSocketError(Throwable failure)
        {
            failMessages(failure);
        }
    }
}
