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
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.UnresolvedAddressException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class WebSocketTransport extends AbstractWebSocketTransport<Session>
{
    private final WebSocketContainer _webSocketContainer;
    private volatile boolean _webSocketSupported = true;
    private volatile boolean _webSocketConnected = false;
    private volatile Session _activeSession;

    public WebSocketTransport(Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketContainer webSocketContainer)
    {
        this(null, options, scheduler, webSocketContainer);
    }

    public WebSocketTransport(String url, Map<String, Object> options, ScheduledExecutorService scheduler, WebSocketContainer webSocketContainer)
    {
        super(url, options, scheduler);
        _webSocketContainer = webSocketContainer;
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

        // JSR 356 does not expose a way to set the connect timeout - ignored
        _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, _webSocketContainer.getDefaultMaxTextMessageBufferSize());
        _webSocketContainer.setDefaultMaxTextMessageBufferSize(maxMessageSize);
    }

    protected void disconnect(String reason)
    {
        Session wsSession = _activeSession;
        _activeSession = null;
        if (wsSession != null && wsSession.isOpen())
        {
            logger.debug("Closing websocket session {}", wsSession);
            try
            {
                wsSession.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, reason));
            }
            catch (IOException x)
            {
                logger.trace("Could not close websocket session " + wsSession, x);
            }
        }
    }

    protected Session connect(String uri, TransportListener listener, List<Mutable> messages)
    {
        Session session = _activeSession;
        if (session != null)
            return session;

        try
        {
            logger.debug("Opening websocket session to {}", uri);

            session = connect(uri);
            _webSocketConnected = true;

            if (isAborted())
                listener.onFailure(new Exception("Aborted"), messages);

            return _activeSession = session;
        }
        catch (ConnectException | SocketTimeoutException | UnresolvedAddressException x)
        {
            // Cannot connect, assume the server supports WebSocket until proved otherwise
            listener.onFailure(x, messages);
        }
        catch (Throwable x)
        {
            _webSocketSupported = isStickyReconnect() && _webSocketConnected;
            listener.onFailure(x, messages);
        }

        return null;
    }

    protected Session connect(String uri) throws IOException
    {
        try
        {
            _webSocketContainer.setDefaultMaxSessionIdleTimeout(getIdleTimeout());
            ClientEndpointConfig.Configurator configurator = new Configurator();
            String protocol = getProtocol();
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .preferredSubprotocols(protocol == null ? null : Collections.singletonList(protocol))
                    .configurator(configurator).build();
            return _webSocketContainer.connectToServer(new CometDWebSocket(), config, new URI(uri));
        }
        catch (DeploymentException | URISyntaxException x)
        {
            throw new IOException(x);
        }
    }

    @Override
    protected void send(Session session, String content, final TransportListener listener, final List<Mutable> messages)
    {
        session.getAsyncRemote().sendText(content, new SendHandler()
        {
            @Override
            public void onResult(SendResult result)
            {
                Throwable failure = result.getException();
                if (failure != null)
                {
                    complete(messages);
                    disconnect("Exception");
                    listener.onFailure(failure, messages);
                }
            }
        });
    }

    private class CometDWebSocket extends Endpoint implements MessageHandler.Whole<String>
    {
        private volatile Session _session;

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
            _session = session;
            logger.debug("Opened websocket session {}", session);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            if (_activeSession == session)
            {
                _activeSession = null;
                logger.debug("Closed websocket connection with code {}: {} ", closeReason, session);
                failMessages(new EOFException("Connection closed " + closeReason));
            }
        }

        @Override
        public void onMessage(String data)
        {
            try
            {
                List<Mutable> messages = parseMessages(data);
                if (_activeSession == _session)
                {
                    logger.debug("Received messages {}", data);
                    onMessages(messages);
                }
                else
                {
                    logger.debug("Discarded messages {}", data);
                }
            }
            catch (ParseException x)
            {
                disconnect("Exception");
                failMessages(x);
            }
        }

        @Override
        public void onError(Session session, Throwable failure)
        {
            failMessages(failure);
        }
    }

    private class Configurator extends ClientEndpointConfig.Configurator
    {
        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            CookieStore cookieStore = getCookieStore();
            List<HttpCookie> cookies = cookieStore.get(URI.create(getURL()));
            if (!cookies.isEmpty())
            {
                List<String> cookieHeader = headers.get("Cookie");
                if (cookieHeader == null)
                    cookieHeader = headers.get("cookie");
                if (cookieHeader == null)
                    headers.put("Cookie", cookieHeader = new ArrayList<>());
                for (HttpCookie cookie : cookies)
                    cookieHeader.add(cookie.getName() + "=" + cookie.getValue());
            }
        }

        @Override
        public void afterResponse(HandshakeResponse hr)
        {
            Map<String, List<String>> headers = hr.getHeaders();

            storeCookies(headers);

            _webSocketSupported = false;
            // Must do case-insensitive search.
            for (String name : headers.keySet())
            {
                if (HandshakeResponse.SEC_WEBSOCKET_ACCEPT.equalsIgnoreCase(name))
                {
                    _webSocketSupported = true;
                    break;
                }
            }
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory
    {
        private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        public Factory()
        {
            // The WebSocketContainer is already started, so we must explicitly manage it.
            addBean(container, true);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options)
        {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new WebSocketTransport(url, options, scheduler, container);
        }
    }
}
