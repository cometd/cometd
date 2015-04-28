/*
 * Copyright (c) 2008-2015 the original author or authors.
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
package org.cometd.websocket.server;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.server.pathmap.ServletPathSpec;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

import javax.servlet.ServletContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class JettyWebSocketTransport extends AbstractWebSocketTransport<Session>
{
    public JettyWebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux);
    }

    @Override
    public void init()
    {
        super.init();

        final ServletContext context = (ServletContext)getOption(ServletContext.class.getName());
        if (context == null)
            throw new IllegalArgumentException("Missing ServletContext");

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING);
        if (cometdURLMapping == null)
            throw new IllegalArgumentException("Missing '" + COMETD_URL_MAPPING + "' parameter");

        WebSocketUpgradeFilter wsFilter = (WebSocketUpgradeFilter)context.getAttribute(WebSocketUpgradeFilter.class.getName());
        if (wsFilter == null)
            throw new IllegalArgumentException("Missing WebSocketUpgradeFilter");

        WebSocketPolicy policy = wsFilter.getFactory().getPolicy();
        int bufferSize = getOption(BUFFER_SIZE_OPTION, policy.getInputBufferSize());
        policy.setInputBufferSize(bufferSize);
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, policy.getMaxTextMessageSize());
        policy.setMaxTextMessageSize(maxMessageSize);
        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, policy.getIdleTimeout());
        policy.setIdleTimeout((int)idleTimeout);

        for (String mapping : normalizeURLMapping(cometdURLMapping))
        {
            wsFilter.addMapping(new ServletPathSpec(mapping), new WebSocketCreator()
            {
                @Override
                public Object createWebSocket(ServletUpgradeRequest request, ServletUpgradeResponse response)
                {
                    String origin = request.getHeader("Origin");
                    if (origin == null)
                        origin = request.getHeader("Sec-WebSocket-Origin");
                    if (checkOrigin(request, origin))
                    {
                        modifyUpgrade(request, response);

                        List<String> allowedTransports = getBayeux().getAllowedTransports();
                        if (allowedTransports.contains(getName()))
                        {
                            WebSocketContext handshake = new WebSocketContext(context, request);
                            return new WebSocketScheduler(handshake);
                        }
                        else
                        {
                            if (_logger.isDebugEnabled())
                                _logger.debug("Transport not those allowed: {}", allowedTransports);
                        }
                    }
                    else
                    {
                        if (_logger.isDebugEnabled())
                            _logger.debug("Origin check failed for origin {}", origin);
                    }
                    return null;
                }
            });
        }
    }

    protected void modifyUpgrade(ServletUpgradeRequest request, ServletUpgradeResponse response)
    {
    }

    @Override
    public void destroy()
    {
        Executor threadPool = getExecutor();
        if (threadPool instanceof LifeCycle)
        {
            try
            {
                ((LifeCycle)threadPool).stop();
            }
            catch (Exception x)
            {
                _logger.trace("", x);
            }
        }
        super.destroy();
    }

    protected boolean checkOrigin(ServletUpgradeRequest request, String origin)
    {
        return true;
    }

    protected void send(final Session wsSession, final ServerSession session, String data, final Callback callback)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("Sending {}", data);

        // First blocking version - but cannot be used for concurrent writes.
//        wsSession.getRemote().sendString(data);

        // Second blocking version - uses Futures, supports concurrent writes.
//        Future<Void> future = wsSession.getRemote().sendStringByFuture(data);
//        try
//        {
//            future.get();
//        }
//        catch (InterruptedException x)
//        {
//            throw new InterruptedIOException();
//        }
//        catch (ExecutionException x)
//        {
//            Throwable cause = x.getCause();
//            if (cause instanceof RuntimeException)
//                throw (RuntimeException)cause;
//            if (cause instanceof Error)
//                throw (Error)cause;
//            if (cause instanceof IOException)
//                throw (IOException)cause;
//            throw new IOException(cause);
//        }

        // Async version.
        wsSession.getRemote().sendString(data, new WriteCallback()
        {
            @Override
            public void writeSuccess()
            {
                callback.succeeded();
            }

            @Override
            public void writeFailed(Throwable x)
            {
                handleException(wsSession, session, x);
                callback.failed(x);
            }
        });
    }

    private class WebSocketScheduler extends AbstractWebSocketScheduler implements WebSocketListener
    {
        private volatile Session _wsSession;

        private WebSocketScheduler(WebSocketContext context)
        {
            super(context);
        }

        public void onWebSocketConnect(Session session)
        {
            _wsSession = session;
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        public void onWebSocketText(String data)
        {
            onMessage(_wsSession, data);
        }

        public void onWebSocketClose(int code, String reason)
        {
            onClose(code, reason);
        }

        @Override
        public void onWebSocketError(Throwable failure)
        {
            onError(failure);
        }

        @Override
        protected void close(int code, String reason)
        {
            _wsSession.close(code, reason);
        }

        @Override
        protected void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply)
        {
            schedule(_wsSession, timeout, expiredConnectReply);
        }
    }

    private class WebSocketContext extends AbstractBayeuxContext
    {
        private final Map<String, Object> attributes;

        private WebSocketContext(ServletContext context, ServletUpgradeRequest request)
        {
            super(context, request.getRequestURI().toString(), request.getQueryString(), request.getHeaders(),
                    request.getParameterMap(), request.getUserPrincipal(), request.getSession(),
                    request.getLocalSocketAddress(), request.getRemoteSocketAddress());
            this.attributes = request.getServletAttributes();
        }

        @Override
        public Object getRequestAttribute(String name)
        {
            return attributes.get(name);
        }
    }
}
