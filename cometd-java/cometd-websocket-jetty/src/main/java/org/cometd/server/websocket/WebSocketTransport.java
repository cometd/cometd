package org.cometd.server.websocket;

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
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.transport.HttpTransport;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.thread.Timeout;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketFactory;

public class WebSocketTransport extends HttpTransport implements WebSocketFactory.Acceptor
{
    public final static String PREFIX = "ws";
    public final static String NAME = "websocket";
    public final static String PROTOCOL_OPTION = "protocol";
    public final static String BUFFER_SIZE_OPTION = "bufferSize";

    private final WebSocketFactory _factory = new WebSocketFactory(this);
    private final ThreadLocal<Handshake> _handshake = new ThreadLocal<Handshake>();
    private String _protocol;

    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init()
    {
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _factory.setBufferSize(getOption(BUFFER_SIZE_OPTION, _factory.getBufferSize()));

        // Change the default values for this transport to better suited ones
        // but only if they were not specifically set for this transport
        setTimeout(getOption(PREFIX + "." + TIMEOUT_OPTION, 15000L));
        setInterval(getOption(PREFIX + "." + INTERVAL_OPTION, 2500L));
        setMaxInterval(getOption(PREFIX + "." + MAX_INTERVAL_OPTION, 15000L));
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "WebSocket".equals(request.getHeader("Upgrade"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (isMetaConnectDeliveryOnly())
        {
            getBayeux().getLogger().warn("MetaConnectDeliveryOnly not implemented for websocket");
            response.setHeader("Connection", "close");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

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
            Handshake handshake = new Handshake(request);
            return new WebSocketScheduler(handshake, request.getHeader("User-Agent"));
        }

        return null;
    }

    public String checkOrigin(HttpServletRequest request, String host, String origin)
    {
        if (origin == null)
            origin = host;
        return origin;
    }

    protected class WebSocketScheduler implements WebSocket.OnTextMessage, AbstractServerTransport.Scheduler
    {
        protected final Handshake _addresses;
        protected final String _userAgent;
        protected ServerSessionImpl _session;
        protected Connection _connection;
        protected ServerMessage.Mutable _connectReply;
        protected final Timeout.Task _timeoutTask = new Timeout.Task()
        {
            @Override
            public void expired()
            {
                // send the meta connect response after timeout.
                if (_session != null)
                {
                    WebSocketScheduler.this.schedule();
                }
            }
        };

        public WebSocketScheduler(Handshake addresses, String userAgent)
        {
            _addresses = addresses;
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
            boolean batch = false;
            try
            {
                WebSocketTransport.this._handshake.set(_addresses);
                getBayeux().setCurrentTransport(WebSocketTransport.this);

                ServerMessage.Mutable[] messages = ServerMessageImpl.parseServerMessages(data);

                for (ServerMessage.Mutable message : messages)
                {
                    boolean connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id = message.getClientId();
                    if (_session == null || client_id != null && !client_id.equals(_session.getId()))
                        _session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());
                    else if (!_session.isHandshook())
                    {
                        batch = false;
                        _session = null;
                    }

                    if (!batch && _session != null && !connect && !message.isMeta())
                    {
                        // start a batch to group all resulting messages into a single response.
                        batch = true;
                        _session.startBatch();
                    }

                    // remember the connected status
                    boolean was_connected = _session != null && _session.isConnected();

                    // handle the message
                    // the actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage.Mutable reply = getBayeux().handle(_session, message);

                    if (connect && reply.isSuccessful())
                    {
                        _session.setUserAgent(_userAgent);
                        _session.setScheduler(this);

                        long timeout = _session.calculateTimeout(getTimeout());

                        if (timeout > 0 && was_connected)
                        {
                            // delay sending connect reply until dispatch or timeout.
                            getBayeux().startTimeout(_timeoutTask, timeout);
                            _connectReply = reply;
                            reply = null;
                        }
                        else if (!was_connected)
                        {
                            _session.startIntervalTimeout();
                        }
                    }

                    // send the reply (if not delayed)
                    if (reply != null)
                    {
                        reply = getBayeux().extendReply(_session, _session, reply);

                        if (batch)
                        {
                            _session.addQueue(reply);
                        }
                        else
                            send(reply);
                    }

                    // disassociate the reply
                    message.setAssociated(null);
                }
            }
            catch (IOException e)
            {
                getBayeux().getLogger().warn("", e);
            }
            catch (ParseException e)
            {
                handleJSONParseException(e.getMessage(), e.getCause());
            }
            finally
            {
                WebSocketTransport.this._handshake.set(null);
                getBayeux().setCurrentTransport(null);
                // if we started a batch - end it now
                if (batch)
                    _session.endBatch();
            }
        }

        protected void handleJSONParseException(String json, Throwable exception)
        {
            getBayeux().getLogger().debug("Error parsing JSON: " + json, exception);
        }

        public void cancel()
        {
        }

        public void schedule()
        {
            // TODO should schedule another thread!
            // otherwise a receive can be blocked writing to another client

            final ServerSessionImpl session = _session;
            if (session != null)
            {
                final List<ServerMessage> queue = session.takeQueue();

                if (_connectReply != null)
                {
                    queue.add(getBayeux().extendReply(session, session, _connectReply));
                    _connectReply = null;
                    session.startIntervalTimeout();
                }
                try
                {
                    if (queue.size() > 0)
                        send(queue);
                }
                catch (IOException e)
                {
                    getBayeux().getLogger().warn("io ", e);
                }
            }
        }

        protected void send(List<ServerMessage> messages) throws IOException
        {
            String data = JSON.toString(messages);
            _connection.sendMessage(data);
        }

        protected void send(ServerMessage message) throws IOException
        {
            String data = message.getJSON();
            _connection.sendMessage("[" + data + "]");
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

    private class Handshake implements BayeuxContext
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
        Handshake(HttpServletRequest request)
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
