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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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

    private final Logger _logger = Log.getLogger(getClass().getName());
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
        _logger.setDebugEnabled(getBayeux().getLogger().isDebugEnabled());
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
                _logger.ignore(x);
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
            _logger.warn("Websocket not accepted");
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
        _logger.debug("Error parsing JSON: " + json, exception);
    }

    protected void handleException(WebSocket.Connection connection, Throwable exception)
    {
        _logger.warn("", exception);
    }

    protected class WebSocketScheduler implements WebSocket.OnTextMessage, AbstractServerTransport.Scheduler, Runnable
    {
        private final AtomicBoolean _scheduling = new AtomicBoolean();
        private final WebSocketContext _context;
        private final String _userAgent;
        private volatile ServerSessionImpl _session;
        private volatile Connection _connection;
        private volatile ServerMessage.Mutable _connectReply;
        private volatile long _connectExpiration;
        private final Timeout.Task _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                long now = System.currentTimeMillis();
                long jitter = now - _connectExpiration;
                if (jitter > 5000) // TODO: make the max jitter a parameter ?
                    _logger.info("/meta/connect timeout expired too late {}", jitter);

                // Send the meta connect response after timeout.
                // We *must* execute schedule(true) otherwise
                // the client will timeout the meta connect, so we
                // do not care about flipping the _scheduling field.
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
            final ServerSessionImpl session = _session;
            if (session != null)
            {
                // There is no need to call BayeuxServerImpl.removeServerSession(),
                // because the connection may have been closed for a reload, so
                // just null out the current session to have it retrieved again
                _session = null;
                session.cancelIntervalTimeout();
                getBayeux().cancelTimeout(_timeoutTask);
            }
        }

        public void onMessage(String data)
        {
            _handshake.set(_context);
            getBayeux().setCurrentTransport(WebSocketTransport.this);
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(data);
                _logger.debug("Received messages {}", data);
                for (ServerMessage.Mutable message : messages)
                    onMessage(message);
            }
            catch (ParseException x)
            {
                handleJSONParseException(_connection, data, x);
            }
            catch (Exception x)
            {
                handleException(_connection, x);
            }
            finally
            {
                _handshake.set(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        protected void onMessage(ServerMessage.Mutable message) throws IOException
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

            // Delegate to BayeuxServer to handle the message.
            // This may trigger server-side listeners that may add messages
            // to the queue, which will trigger a schedule() via flush()

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
                            // Decide atomically if we need to hold the meta connect or not
                            // In schedule() we decide atomically if reply to the meta connect
                            synchronized (session.getLock())
                            {
                                if (session.isQueueEmpty())
                                {
                                    // Delay the connect reply until timeout.
                                    _connectReply = reply;
                                    _connectExpiration = System.currentTimeMillis() + timeout;
                                    reply = null;
                                }
                                else
                                {
                                    queue = session.takeQueue();
                                }
                            }
                            if (reply == null)
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
        }

        public void cancel()
        {
            getBayeux().cancelTimeout(_timeoutTask);
        }

        public void schedule()
        {
            // This method may be called concurrently, for example when 2 clients
            // publish concurrently on the same channel.
            // We must avoid to dispatch multiple times, to save threads.
            // However, the CAS operation introduces a window where a schedule()
            // is skipped and the queue remains full; to avoid this situation,
            // we reschedule at the end of schedule(boolean).
            if (_scheduling.compareAndSet(false, true))
                _threadPool.execute(this);
        }

        public void run()
        {
            schedule(false);
        }

        private void schedule(boolean timeout)
        {
            // This method may be executed concurrently by a thread triggered by
            // schedule() and by the timeout thread that replies to the meta connect.

            boolean reschedule = false;
            ServerSessionImpl session = _session;
            try
            {
                if (session == null)
                    return;

                // Decide atomically if we have to reply to the meta connect
                // We need to guarantee the metaConnectDeliverOnly semantic
                // and allow only one thread to reply to the meta connect
                // otherwise we may have out of order delivery.
                boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                boolean disconnected = !session.isConnected();
                boolean reply = false;
                ServerMessage.Mutable connectReply;
                synchronized (session.getLock())
                {
                    connectReply = _connectReply;

                    if (connectReply == null)
                    {
                        if (metaConnectDelivery)
                        {
                            // If we need to deliver only via meta connect, but we
                            // do not have one outstanding, wait until it arrives
                            _logger.debug("Flushing skipped since metaConnectDelivery={}, metaConnectReply={}", metaConnectDelivery, connectReply);
                            return;
                        }
                    }
                    else
                    {
                        if (timeout || disconnected || metaConnectDelivery)
                        {
                            _connectReply = null;
                            reply = true;
                        }
                    }
                }

                reschedule = true;
                List<ServerMessage> queue = session.takeQueue();

                if (reply)
                {
                    if (disconnected)
                        connectReply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

                    connectReply = getBayeux().extendReply(session, session, connectReply);
                    if (connectReply != null)
                        queue.add(connectReply);

                    if (!disconnected)
                        session.startIntervalTimeout(getInterval());
                }

                if (queue.size() > 0)
                {
                    _logger.debug("Flushing {} timeout={} metaConnectDelivery={}, metaConnectReply={}, messages={}", session, timeout, metaConnectDelivery, reply, queue);
                    send(queue);
                }
            }
            catch (Exception x)
            {
                handleException(_connection, x);
            }
            finally
            {
                if (!timeout)
                    _scheduling.compareAndSet(true, false);

                if (reschedule && !session.isQueueEmpty())
                    schedule();
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
            _logger.debug("Sending {}", builder);
            _connection.sendMessage(builder.toString());
        }

        protected void send(ServerMessage message) throws IOException
        {
            StringBuilder builder = new StringBuilder(message.size() * 32).append("[").append(message.getJSON()).append("]");
            _logger.debug("Sending {}", builder);
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
