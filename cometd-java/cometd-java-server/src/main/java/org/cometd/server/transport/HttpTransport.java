package org.cometd.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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
            return ServerMessageImpl.parseMessages(request.getReader(), _jsonDebug);

        String[] batches=request.getParameterValues(MESSAGE_PARAM);

        if (batches == null || batches.length == 0)
            return null;

        if (batches.length == 1)
            return ServerMessageImpl.parseMessages(batches[0]);

        List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
        for (String batch : batches)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(ServerMessageImpl.parseMessages(batch)));
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
            return new InetSocketAddress(_request.getRemoteHost(),_request.getRemotePort());
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(_request.getLocalName(),_request.getLocalPort());
        }

        @Override
        public Collection<String> getHeaderNames()
        {
            return Collections.list((Enumeration<String>)_request.getHeaderNames());
        }

        @Override
        public Collection<String> getParameterNames()
        {
            return Collections.list((Enumeration<String>)_request.getParameterNames());
        }

        @Override
        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        @Override
        public List<String> getHeaderValues(String name)
        {
            return Collections.list((Enumeration<String>)_request.getHeaders(name));
        }

        @Override
        public String getParameter(String name)
        {
            return _request.getParameter(name);
        }

        @Override
        public List<String> getParameterValues(String name)
        {
            return Arrays.asList(_request.getParameterValues(name));
        }

        @Override
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

        @Override
        public String getHttpSessionId()
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                return session.getId();
            return null;
        }

        @Override
        public Collection<String> getHttpSesionAttributeNames()
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                return Collections.list(session.getAttributeNames());
            return null;
        }

        @Override
        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                return session.getAttribute(name);
            return null;
        }

        @Override
        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                session.setAttribute(name,value);
            else
                throw new IllegalStateException("!session");
        }

        @Override
        public void invalidateHttpSession()
        {
            HttpSession session = _request.getSession(false);
            if (session!=null)
                session.invalidate();
        }
    }

}
