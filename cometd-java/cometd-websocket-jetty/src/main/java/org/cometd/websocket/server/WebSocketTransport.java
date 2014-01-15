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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;

public class WebSocketTransport extends AbstractWebSocketTransport<WebSocket.Connection> implements WebSocketFactory.Acceptor
{
    public static final String BUFFER_SIZE_OPTION = "bufferSize";

    private final WebSocketFactory _factory = new WebSocketFactory(this);

    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux);
    }

    @Override
    public void init()
    {
        super.init();
        int bufferSize = getOption(BUFFER_SIZE_OPTION, _factory.getBufferSize());
        _factory.setBufferSize(bufferSize);
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, bufferSize - 16);
        _factory.setMaxTextMessageSize(maxMessageSize);
        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, _factory.getMaxIdleTime());
        _factory.setMaxIdleTime((int)idleTimeout);
        try
        {
            _factory.start();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }

    @Override
    protected void destroy()
    {
        try
        {
            _factory.stop();
        }
        catch (Exception x)
        {
            _logger.trace("", x);
        }

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

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!_factory.acceptWebSocket(request, response))
        {
            _logger.warn("WebSocket not accepted");
            if (!response.isCommitted())
            {
                response.setHeader("Connection", "close");
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        String serverProtocol = getProtocol();
        boolean sameProtocol = (serverProtocol == null && protocol == null) ||
                (serverProtocol != null && serverProtocol.equals(protocol));

        if (sameProtocol)
        {
            WebSocketContext handshake = new WebSocketContext(request);
            return new WebSocketScheduler(handshake);
        }

        _logger.warn("WebSocket protocols do not match: server[{}] != client[{}]", serverProtocol, protocol);

        return null;
    }

    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }

    @Override
    protected void send(WebSocket.Connection wsSession, ServerSession session, String data) throws IOException
    {
        debug("Sending {}", data);
        wsSession.sendMessage(data);
    }

    private class WebSocketScheduler extends AbstractWebSocketScheduler implements WebSocket.OnTextMessage
    {
        private volatile Connection _connection;

        public WebSocketScheduler(WebSocketContext context)
        {
            super(context);
        }

        public void onOpen(Connection connection)
        {
            _connection = connection;
        }

        public void onMessage(String data)
        {
            onMessage(_connection, data);
        }

        @Override
        public void onClose(int code, String reason)
        {
            super.onClose(code, reason);
        }

        @Override
        protected void close(int code, String reason)
        {
            _connection.close(code, reason);
        }

        @Override
        protected void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply)
        {
            schedule(_connection, timeout, expiredConnectReply);
        }
    }

    private class WebSocketContext implements BayeuxContext
    {
        private final Principal _principal;
        private final InetSocketAddress _local;
        private final InetSocketAddress _remote;
        private final Map<String, List<String>> _headers = new HashMap<String, List<String>>();
        private final Map<String, List<String>> _parameters = new HashMap<String, List<String>>();
        private final Map<String, Object> _attributes = new HashMap<String, Object>();
        private final Map<String, String> _cookies = new HashMap<String, String>();
        private final HttpSession _session;
        private final ServletContext _context;
        private final String _url;

        @SuppressWarnings("unchecked")
        public WebSocketContext(HttpServletRequest request)
        {
            _local = new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
            _remote = new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());

            for (String name : Collections.list((Enumeration<String>)request.getHeaderNames()))
                _headers.put(name.toLowerCase(Locale.ENGLISH), Collections.unmodifiableList(Collections.list(request.getHeaders(name))));

            for (String name : Collections.list((Enumeration<String>)request.getParameterNames()))
                _parameters.put(name, Collections.unmodifiableList(Arrays.asList(request.getParameterValues(name))));

            for (String name : Collections.list((Enumeration<String>)request.getAttributeNames()))
                _attributes.put(name, request.getAttribute(name));

            Cookie[] cookies = request.getCookies();
            if (cookies != null)
            {
                for (Cookie c : cookies)
                    _cookies.put(c.getName(), c.getValue());
            }

            _principal = request.getUserPrincipal();

            _session = request.getSession(false);
            if (_session != null)
            {
                _context = _session.getServletContext();
            }
            else
            {
                ServletContext context = null;
                try
                {
                    HttpSession s = request.getSession(true);
                    context = s.getServletContext();
                    s.invalidate();
                }
                catch (IllegalStateException x)
                {
                    _logger.trace("", x);
                }
                finally
                {
                    _context = context;
                }
            }

            StringBuffer url = request.getRequestURL();
            String query = request.getQueryString();
            if (query != null)
                url.append("?").append(query);
            this._url = url.toString();
        }

        public Principal getUserPrincipal()
        {
            return _principal;
        }

        public boolean isUserInRole(String role)
        {
            HttpServletRequest request = WebSocketTransport.this.getCurrentRequest();
            return request != null && request.isUserInRole(role);
        }

        public InetSocketAddress getRemoteAddress()
        {
            return _remote;
        }

        public InetSocketAddress getLocalAddress()
        {
            return _local;
        }

        public String getHeader(String name)
        {
            List<String> headers = _headers.get(name.toLowerCase(Locale.ENGLISH));
            return headers != null && headers.size() > 0 ? headers.get(0) : null;
        }

        public List<String> getHeaderValues(String name)
        {
            return _headers.get(name.toLowerCase(Locale.ENGLISH));
        }

        public String getParameter(String name)
        {
            List<String> params = _parameters.get(name);
            return params != null && params.size() > 0 ? params.get(0) : null;
        }

        public List<String> getParameterValues(String name)
        {
            return _parameters.get(name);
        }

        public String getCookie(String name)
        {
            return _cookies.get(name);
        }

        public String getHttpSessionId()
        {
            return _session == null ? null : _session.getId();
        }

        public Object getHttpSessionAttribute(String name)
        {
            return _session == null ? null : _session.getAttribute(name);
        }

        public void setHttpSessionAttribute(String name, Object value)
        {
            if (_session != null)
                _session.setAttribute(name, value);
            else
                throw new IllegalStateException("!session");
        }

        public void invalidateHttpSession()
        {
            if (_session != null)
                _session.invalidate();
        }

        public Object getRequestAttribute(String name)
        {
            return _attributes.get(name);
        }

        public Object getContextAttribute(String name)
        {
            return _context.getAttribute(name);
        }

        public String getContextInitParameter(String name)
        {
            return _context.getInitParameter(name);
        }

        public String getURL()
        {
            return _url;
        }
    }
}
