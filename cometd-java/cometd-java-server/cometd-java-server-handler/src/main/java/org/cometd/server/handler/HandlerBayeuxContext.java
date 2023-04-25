package org.cometd.server.handler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.server.spi.CometDCookie;
import org.eclipse.jetty.server.Request;

class HandlerBayeuxContext implements BayeuxContext
{
    private final HandlerCometDRequest cometDRequest;
    private final Request request;

    public HandlerBayeuxContext(HandlerCometDRequest cometDRequest, Request request)
    {
        this.cometDRequest = cometDRequest;
        this.request = request;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return null;
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return false;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        SocketAddress socketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress;
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        SocketAddress socketAddress = request.getConnectionMetaData().getLocalSocketAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress;
        return null;
    }

    @Override
    public String getHeader(String name)
    {
        return request.getHeaders().get(name);
    }

    @Override
    public List<String> getHeaderValues(String name)
    {
        return request.getHeaders().getValuesList(name);
    }

    @Override
    public String getParameter(String name)
    {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public List<String> getParameterValues(String name)
    {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public String getCookie(String name)
    {
        return Arrays.stream(cometDRequest.getCookies())
            .filter(cometDCookie -> cometDCookie.getName().equals(name))
            .map(CometDCookie::getValue)
            .findFirst()
            .orElse(null);
    }

    @Override
    public String getHttpSessionId()
    {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Object getHttpSessionAttribute(String name)
    {
        return null;
    }

    @Override
    public void setHttpSessionAttribute(String name, Object value)
    {

    }

    @Override
    public void invalidateHttpSession()
    {

    }

    @Override
    public Object getRequestAttribute(String name)
    {
        return null;
    }

    @Override
    public Object getContextAttribute(String name)
    {
        return null;
    }

    @Override
    public String getContextInitParameter(String name)
    {
        return null;
    }

    @Override
    public String getContextPath()
    {
        return request.getContext().getContextPath();
    }

    @Override
    public String getURL()
    {
        return null;
    }

    @Override
    public List<Locale> getLocales()
    {
        return null;
    }

    @Override
    public String getProtocol()
    {
        return request.getConnectionMetaData().getProtocol();
    }

    @Override
    public boolean isSecure()
    {
        return request.getConnectionMetaData().isSecure();
    }
}
