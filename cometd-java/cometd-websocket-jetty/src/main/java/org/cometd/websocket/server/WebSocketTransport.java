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
import java.net.InetSocketAddress;
import java.security.Principal;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.transport.HttpTransport;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;

public class WebSocketTransport extends HttpTransport implements WebSocketFactory.Acceptor
{
    public static final String PREFIX = "ws";
    public static final String NAME = "websocket";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String BUFFER_SIZE_OPTION = "bufferSize";
    public static final String THREAD_POOL_MAX_SIZE = "threadPoolMaxSize";

    private final WebSocketFactory _factory = new WebSocketFactory(this);
    private final ThreadLocal<WebSocketContext> _handshake = new ThreadLocal<WebSocketContext>();
    private String _protocol;
    private Executor _threadPool;

    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init()
    {
        super.init();
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _factory.setBufferSize(getOption(BUFFER_SIZE_OPTION, _factory.getBufferSize()));
        _threadPool = newThreadPool();
    }

    @Override
    protected void destroy()
    {
        Executor threadPool = _threadPool;
        if (threadPool instanceof ExecutorService)
        {
            ((ExecutorService)threadPool).shutdown();
        }
        else if (threadPool instanceof LifeCycle)
        {
            try
            {
                ((LifeCycle)threadPool).stop();
            }
            catch (Exception x)
            {
                getBayeux().getLogger().ignore(x);
            }
        }
        super.destroy();
    }

    protected Executor newThreadPool()
    {
        int size = getOption(THREAD_POOL_MAX_SIZE, 64);
        return Executors.newFixedThreadPool(size);
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "WebSocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!_factory.acceptWebSocket(request, response))
        {
            getBayeux().getLogger().warn("Websocket not accepted");
            response.setHeader("Connection", "close");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        boolean sameProtocol = (_protocol == null && protocol == null) ||
                (_protocol != null && _protocol.equals(protocol));

        if (sameProtocol)
        {
            WebSocketContext handshake = new WebSocketContext(request);
            return new WebSocketScheduler(handshake, request.getHeader("User-Agent"));
        }

        return null;
    }

    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }

    protected void handleJSONParseException(WebSocket.Connection connection, String json, Throwable exception)
    {
        getBayeux().getLogger().debug("Error parsing JSON: " + json, exception);
    }

    protected void handleException(WebSocket.Connection connection, Throwable exception)
    {
        getBayeux().getLogger().warn("", exception);
    }

    protected class WebSocketScheduler implements WebSocket.OnTextMessage, AbstractServerTransport.Scheduler, Runnable
    {
        protected final WebSocketContext _context;
        protected final String _userAgent;
        protected ServerSessionImpl _session;
        protected Connection _connection;
        protected volatile ServerMessage.Mutable _connectReply;
        protected final Timeout.Task _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                // Send the meta connect response after timeout.
                if (_session != null)
                    WebSocketScheduler.this.schedule(true);
            }
        };

        public WebSocketScheduler(WebSocketContext context, String userAgent)
        {
            _context = context;
            _userAgent = userAgent;
        }

        public void onOpen(Connection connection)
        {
            _connection = connection;
        }

        public void onClose(int code, String message)
        {
            if (_session != null)
            {
                _session.cancelIntervalTimeout();
                getBayeux().cancelTimeout(_timeoutTask);
                getBayeux().removeServerSession(_session, false);
            }
        }

        public void onMessage(String data)
        {
            try
            {
                _handshake.set(_context);
                getBayeux().setCurrentTransport(WebSocketTransport.this);

                ServerMessage.Mutable[] messages = parseMessages(data);

                for (ServerMessage.Mutable message : messages)
                {
                    boolean connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    ServerSessionImpl session = _session;
                    String clientId = message.getClientId();
                    if (session == null || !session.getId().equals(clientId))
                    {
                        session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());
                        _session = session;
                    }

                    // Session expired concurrently ?
                    if (session != null && !session.isHandshook())
                    {
                        session = null;
                        _session = session;
                    }

                    // Remember the connected status
                    boolean wasConnected = session != null && session.isConnected();

                    // Handle the message.
                    // The actual reply is return from the call, but
                    // other messages may also be queued on the session.
                    ServerMessage.Mutable reply = getBayeux().handle(session, message);
                    if (reply != null)
                    {
                        if (session == null)
                        {
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                            if (session != null)
                            {
                                session.setUserAgent(_userAgent);
                                session.setScheduler(this);
                            }
                        }

                        List<ServerMessage> queue = null;

                        // Check again if session is connected, BayeuxServer.handle() may have caused a disconnection
                        if (connect && reply.isSuccessful() && session != null && session.isConnected())
                        {
                            // We need to set the scheduler again, in case the connection
                            // has temporarily broken and we have created a new scheduler
                            session.setScheduler(this);

                            // If we deliver only via meta connect, and we have messages,
                            // we need to send the queue and the meta connect reply
                            boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                            boolean hasMessages = !session.isQueueEmpty();
                            boolean replyToMetaConnect = hasMessages && metaConnectDelivery;
                            if (replyToMetaConnect)
                            {
                                queue = session.takeQueue();
                            }
                            else
                            {
                                long timeout = session.calculateTimeout(getTimeout());
                                boolean holdMetaConnect = timeout > 0 && wasConnected;
                                if (holdMetaConnect)
                                {
                                    // Delay the connect reply until timeout.
                                    _connectReply = reply;
                                    getBayeux().getLogger().debug("{}@{} holding meta connect, reply: {}", getClass().getSimpleName(), System.identityHashCode(this), reply);
                                    reply = null;
                                    getBayeux().startTimeout(_timeoutTask, timeout);
                                }
                            }
                        }

                        // Send the reply
                        if (reply != null)
                        {
                            reply = getBayeux().extendReply(session, session, reply);
                            if (reply != null)
                            {
                                // Check again if session is connected, extensions may have disconnected
                                if (connect && session != null && session.isConnected())
                                    session.startIntervalTimeout(getInterval());

                                if (queue != null)
                                {
                                    queue.add(reply);
                                    send(queue);
                                }
                                else
                                {
                                    send(reply);
                                }
                            }
                        }
                    }

                    // Disassociate the reply
                    message.setAssociated(null);
                }
            }
            catch (IOException x)
            {
                handleException(_connection, x);
            }
            catch (ParseException x)
            {
                handleJSONParseException(_connection, data, x);
            }
            finally
            {
                _handshake.set(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        public void cancel()
        {
        }

        public void schedule()
        {
            _threadPool.execute(this);
        }

        public void run()
        {
            schedule(false);
        }

        private void schedule(boolean timeout)
        {
            ServerSessionImpl session = _session;
            if (session == null)
                return;

            boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
            ServerMessage.Mutable reply = _connectReply;

            // If we need to deliver only via meta connect,
            // but we do not have one, wait until it arrives
            if (metaConnectDelivery && reply == null)
                return;

            List<ServerMessage> queue = session.takeQueue();

            if (reply != null)
            {
                boolean disconnected = !session.isConnected();
                if (timeout || disconnected || metaConnectDelivery)
                {
                    if (disconnected)
                        reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

                    reply = getBayeux().extendReply(session, session, reply);
                    if (reply != null)
                        queue.add(reply);

                    _connectReply = null;

                    if (!disconnected)
                        session.startIntervalTimeout(getInterval());
                }
            }

            try
            {
                if (queue.size() > 0)
                    send(queue);
            }
            catch (IOException x)
            {
                handleException(_connection, x);
            }
        }

        protected void send(List<ServerMessage> messages) throws IOException
        {
            StringBuilder builder = new StringBuilder(messages.size() * 4 * 32).append("[");
            for (int i = 0; i < messages.size(); i++)
            {
                if (i > 0)
                    builder.append(",");
                ServerMessage serverMessage = messages.get(i);
                builder.append(serverMessage.getJSON());
            }
            builder.append("]");
            _connection.sendMessage(builder.toString());
        }

        protected void send(ServerMessage message) throws IOException
        {
            StringBuilder builder = new StringBuilder(message.size() * 32).append("[").append(message.getJSON()).append("]");
            _connection.sendMessage(builder.toString());
        }
    }

    /**
     * @see org.cometd.server.transport.HttpTransport#getContext()
     */
    @Override
    public BayeuxContext getContext()
    {
        return _handshake.get();
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
        WebSocketContext(HttpServletRequest request)
        {
            _local = new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
            _remote = new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());

            for (String name : Collections.list((Enumeration<String>)request.getHeaderNames()))
                _headers.put(name, Collections.unmodifiableList(Collections.list(request.getHeaders(name))));

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
                HttpSession s = request.getSession(true);
                _context = s.getServletContext();
                s.invalidate();
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
            List<String> headers = _headers.get(name);
            return headers != null && headers.size() > 0 ? headers.get(0) : null;
        }

        public List<String> getHeaderValues(String name)
        {
            return _headers.get(name);
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
