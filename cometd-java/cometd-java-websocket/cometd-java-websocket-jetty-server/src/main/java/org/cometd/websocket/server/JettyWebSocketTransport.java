/*
 * Copyright (c) 2010 the original author or authors.
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
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
    protected void init()
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
                    WebSocketContext handshake = new WebSocketContext(context, request);
                    return new WebSocketScheduler(handshake);
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

    protected boolean checkOrigin(ServletUpgradeRequest request, String origin)
    {
        return true;
    }

    protected void send(Session wsSession, ServerSession session, String data) throws IOException
    {
        debug("Sending {}", data);

        // First blocking version - but cannot be used for concurrent writes
//        wsSession.getRemote().sendString(data);

        // Second blocking version - uses Futures
        Future<Void> future = wsSession.getRemote().sendStringByFuture(data);
        try
        {
            future.get();
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            if (cause instanceof Error)
                throw (Error)cause;
            if (cause instanceof IOException)
                throw (IOException)cause;
            throw new IOException(cause);
        }

        // Async version
//        wsSession.getRemote().sendString(data, new WriteCallback()
//        {
//            @Override
//            public void writeSuccess()
//            {
//            }
//
//            @Override
//            public void writeFailed(Throwable x)
//            {
//                // TODO: log failure
//            }
//        });
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

        public void onWebSocketClose(int code, String reason)
        {
            onClose(code, reason);
        }

        @Override
        public void onWebSocketError(Throwable failure)
        {
            onError(failure);
            // TODO: more to do ?
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {
        }

        public void onWebSocketText(String data)
        {
            onMessage(_wsSession, data);
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
        private final ServletContext _context;
        private final ServletUpgradeRequest _request;

        private WebSocketContext(ServletContext context, ServletUpgradeRequest request)
        {
            _context = context;
            _request = request;
        }

        @Override
        public Principal getUserPrincipal()
        {
            return _request.getUserPrincipal();
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return _request.isUserInRole(role);
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return _request.getRemoteSocketAddress();
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return _request.getLocalSocketAddress();
        }

        @Override
        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        @Override
        public List<String> getHeaderValues(String name)
        {
            return _request.getHeaders().get(name);
        }

        public String getParameter(String name)
        {
            List<String> parameters = getParameterValues(name);
            if (parameters != null && !parameters.isEmpty())
                return parameters.get(0);
            return null;
        }

        @Override
        public List<String> getParameterValues(String name)
        {
            return _request.getServletParameters().get(name);
        }

        @Override
        public String getCookie(String name)
        {
            for (HttpCookie cookie : _request.getCookies())
            {
                if (cookie.getName().equals(name))
                    return cookie.getValue();
            }
            return null;
        }

        @Override
        public String getHttpSessionId()
        {
            HttpSession session = (HttpSession)_request.getSession();
            return session == null ? null : session.getId();
        }

        @Override
        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = (HttpSession)_request.getSession();
            return session == null ? null : session.getAttribute(name);
        }

        @Override
        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = (HttpSession)_request.getSession();
            if (session != null)
                session.setAttribute(name, value);
        }

        @Override
        public void invalidateHttpSession()
        {
            HttpSession session = (HttpSession)_request.getSession();
            if (session != null)
                session.invalidate();
        }

        @Override
        public Object getRequestAttribute(String name)
        {
            return _request.getServletAttribute(name);
        }

        @Override
        public Object getContextAttribute(String name)
        {
            return _context.getAttribute(name);
        }

        @Override
        public String getContextInitParameter(String name)
        {
            return _context.getInitParameter(name);
        }

        @Override
        public String getURL()
        {
            String url = _request.getRequestURI().toString();
            String query = _request.getQueryString();
            if (query != null)
                url += "?" + query;
            return url;
        }
    }
}
