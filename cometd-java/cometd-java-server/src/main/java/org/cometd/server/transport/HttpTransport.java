package org.cometd.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;


/**
 * HTTP Transport base class.
 *
 * Used for transports that use HTTP for a transport or to initiate a transport connection.
 *
 */
public abstract class HttpTransport extends AbstractServerTransport
{
    public static final String JSON_DEBUG_OPTION="jsonDebug";
    public static final String MESSAGE_PARAM="message";

    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private boolean _jsonDebug = false;

    protected HttpTransport(BayeuxServerImpl bayeux,String name)
    {
        super(bayeux,name);
    }

    @Override
    protected void init()
    {
        super.init();
        _jsonDebug = getOption(JSON_DEBUG_OPTION, _jsonDebug);
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request)
            throws IOException, ParseException
    {
        String content_type=request.getContentType();

        // Get message batches either as JSON body or as message parameters
        if (content_type!=null && !content_type.startsWith("application/x-www-form-urlencoded"))
            return ServerMessageImpl.parseServerMessages(request.getReader(), _jsonDebug);

        String[] batches=request.getParameterValues(MESSAGE_PARAM);

        if (batches == null || batches.length == 0)
            return null;

        if (batches.length == 1)
            return ServerMessageImpl.parseServerMessages(batches[0]);

        List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
        for (String batch : batches)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(ServerMessageImpl.parseServerMessages(batch)));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }

    /* ------------------------------------------------------------ */
    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }
    /* ------------------------------------------------------------ */

    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.ServerTransport#getCurrentLocalAddress()
     */
    public InetSocketAddress getCurrentLocalAddress()
    {
        BayeuxContext context = getContext();
        if (context!=null)
            return context.getLocalAddress();

        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.ServerTransport#getCurrentRemoteAddress()
     */
    public InetSocketAddress getCurrentRemoteAddress()
    {
        BayeuxContext context = getContext();
        if (context!=null)
            return context.getRemoteAddress();

        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.ServerTransport#getContext()
     */
    public BayeuxContext getContext()
    {
        HttpServletRequest request=getCurrentRequest();
        if (request!=null)
            return new HttpContext(request);
        return null;
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class HttpContext implements BayeuxContext
    {
        final HttpServletRequest _request;

        HttpContext(HttpServletRequest request)
        {
            _request=request;
        }

        public Principal getUserPrincipal()
        {
            return _request.getUserPrincipal();
        }

        public boolean isUserInRole(String role)
        {
            return _request.isUserInRole(role);
        }

        public InetSocketAddress getRemoteAddress()
        {
            return new InetSocketAddress(_request.getRemoteHost(),_request.getRemotePort());
        }

        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(_request.getLocalName(),_request.getLocalPort());
        }


        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        public List<String> getHeaderValues(String name)
        {
            return Collections.list((Enumeration<String>)_request.getHeaders(name));
        }

        public String getParameter(String name)
        {
            return _request.getParameter(name);
        }

        public List<String> getParameterValues(String name)
        {
            return Arrays.asList(_request.getParameterValues(name));
        }

        public String getCookie(String name)
        {
            Cookie[] cookies = _request.getCookies();
            for (Cookie c : cookies)
            {
                if (name.equals(c.getName()))
                    return c.getValue();
            }
            return null;
        }

        public String getHttpSessionId()
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                return session.getId();
            return null;
        }

        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                return session.getAttribute(name);
            return null;
        }

        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                session.setAttribute(name,value);
            else
                throw new IllegalStateException("!session");
        }

        public void invalidateHttpSession()
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                session.invalidate();
        }

        public Object getRequestAttribute(String name)
        {
            return _request.getAttribute(name);
        }

        private ServletContext getServletContext()
        {
            ServletContext c = null;
            HttpSession s = _request.getSession(false);
            if (s!=null)
                c=s.getServletContext();
            else
            {
                s=_request.getSession(true);
                c=s.getServletContext();
                s.invalidate();
            }
            return c;
        }

        public Object getContextAttribute(String name)
        {
            return getServletContext().getAttribute(name);
        }

        public String getContextInitParameter(String name)
        {
            return getServletContext().getInitParameter(name);
        }

        public String getURL()
        {
            StringBuffer url = _request.getRequestURL();
            String query = _request.getQueryString();
            if (query != null)
                url.append("?").append(query);
            return url.toString();
        }
    }
}
