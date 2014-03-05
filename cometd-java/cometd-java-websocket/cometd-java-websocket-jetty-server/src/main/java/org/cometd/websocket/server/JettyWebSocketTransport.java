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
package org.cometd.websocket.server;

import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
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

        WebSocketUpgradeFilter wsFilter = (WebSocketUpgradeFilter)context.getAttribute(WebSocketUpgradeFilter.class.getName());
        if (wsFilter == null)
            throw new IllegalArgumentException("Missing WebSocketUpgradeFilter");

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING);
        if (cometdURLMapping == null)
            throw new IllegalArgumentException("Missing URL Mapping");

        if (cometdURLMapping.endsWith("/*"))
            cometdURLMapping = cometdURLMapping.substring(0, cometdURLMapping.length() - 2);

        wsFilter.addMapping(new ServletPathSpec(cometdURLMapping), new WebSocketCreator()
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
                        _logger.debug("Transport not those allowed: {}", allowedTransports);
                    }
                }
                else
                {
                    _logger.debug("Origin check failed for origin {}", origin);
                }
                return null;
            }
        });

        WebSocketPolicy policy = wsFilter.getFactory().getPolicy();
        int bufferSize = getOption(BUFFER_SIZE_OPTION, policy.getInputBufferSize());
        policy.setInputBufferSize(bufferSize);
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, policy.getMaxTextMessageSize());
        policy.setMaxTextMessageSize(maxMessageSize);
        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, policy.getIdleTimeout());
        policy.setIdleTimeout((int)idleTimeout);
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

    protected void send(final Session wsSession, final ServerSession session, String data)
    {
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
            }

            @Override
            public void writeFailed(Throwable x)
            {
                handleException(wsSession, session, x);
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

    private class WebSocketContext implements BayeuxContext
    {
        private final ServletContext context;
        private final InetSocketAddress localAddress;
        private final InetSocketAddress remoteAddress;
        private final String url;
        private final Principal principal;
        private final Map<String, List<String>> headers;
        private final Map<String, List<String>> parameters;
        private final Map<String, Object> attributes;
        private final HttpSession session;

        private WebSocketContext(ServletContext context, ServletUpgradeRequest request)
        {
            this.context = context;
            // Must copy everything from the request, it may be gone afterwards.
            this.localAddress = request.getLocalSocketAddress();
            this.remoteAddress = request.getRemoteSocketAddress();
            String uri = request.getRequestURI().toString();
            String query = request.getQueryString();
            if (query != null)
                uri += "?" + query;
            this.url = uri;
            this.principal = request.getUserPrincipal();
            this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            this.headers.putAll(request.getHeaders());
            this.parameters = request.getParameterMap();
            this.attributes = request.getServletAttributes();
            // Assume the HttpSession does not go away immediately after the upgrade.
            this.session = request.getSession();
        }

        @Override
        public Principal getUserPrincipal()
        {
            return principal;
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return false;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return remoteAddress;
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return localAddress;
        }

        @Override
        public String getHeader(String name)
        {
            List<String> values = headers.get(name);
            return values != null && values.size() > 0 ? values.get(0) : null;
        }

        @Override
        public List<String> getHeaderValues(String name)
        {
            return headers.get(name);
        }

        public String getParameter(String name)
        {
            List<String> values = parameters.get(name);
            return values != null && values.size() > 0 ? values.get(0) : null;
        }

        @Override
        public List<String> getParameterValues(String name)
        {
            return parameters.get(name);
        }

        @Override
        public String getCookie(String name)
        {
            List<String> values = headers.get("Cookie");
            if (values != null)
            {
                for (String value : values)
                {
                    for (HttpCookie cookie : HttpCookie.parse(value))
                    {
                        if (cookie.getName().equals(name))
                            return cookie.getValue();
                    }
                }
            }
            return null;
        }

        @Override
        public String getHttpSessionId()
        {
            return session == null ? null : session.getId();
        }

        @Override
        public Object getHttpSessionAttribute(String name)
        {
            return session == null ? null : session.getAttribute(name);
        }

        @Override
        public void setHttpSessionAttribute(String name, Object value)
        {
            if (session != null)
                session.setAttribute(name, value);
        }

        @Override
        public void invalidateHttpSession()
        {
            if (session != null)
                session.invalidate();
        }

        @Override
        public Object getRequestAttribute(String name)
        {
            return attributes.get(name);
        }

        @Override
        public Object getContextAttribute(String name)
        {
            return context.getAttribute(name);
        }

        @Override
        public String getContextInitParameter(String name)
        {
            return context.getInitParameter(name);
        }

        @Override
        public String getURL()
        {
            return url;
        }
    }
}
