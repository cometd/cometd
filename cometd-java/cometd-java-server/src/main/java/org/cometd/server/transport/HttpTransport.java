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

package org.cometd.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
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
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;

/**
 * <p>HTTP ServerTransport base class, used by ServerTransports that use
 * HTTP as transport or to initiate a transport connection.</p>
 */
public abstract class HttpTransport extends AbstractServerTransport
{
    public static final String JSON_DEBUG_OPTION = "jsonDebug";
    public static final String MESSAGE_PARAM = "message";

    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();

    protected HttpTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }

    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }

    public BayeuxContext getContext()
    {
        HttpServletRequest request = getCurrentRequest();
        if (request != null)
            return new HttpContext(request);
        return null;
    }

    private static class HttpContext implements BayeuxContext
    {
        final HttpServletRequest _request;

        HttpContext(HttpServletRequest request)
        {
            _request = request;
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
            return new InetSocketAddress(_request.getRemoteHost(), _request.getRemotePort());
        }

        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(_request.getLocalName(), _request.getLocalPort());
        }

        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        @SuppressWarnings("unchecked")
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
            if (cookies != null)
            {
                for (Cookie c : cookies)
                {
                    if (name.equals(c.getName()))
                        return c.getValue();
                }
            }
            return null;
        }

        public String getHttpSessionId()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getId();
            return null;
        }

        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getAttribute(name);
            return null;
        }

        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.setAttribute(name, value);
            else
                throw new IllegalStateException("!session");
        }

        public void invalidateHttpSession()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.invalidate();
        }

        public Object getRequestAttribute(String name)
        {
            return _request.getAttribute(name);
        }

        private ServletContext getServletContext()
        {
            HttpSession s = _request.getSession(false);
            if (s != null)
            {
                return s.getServletContext();
            }
            else
            {
                s = _request.getSession(true);
                ServletContext servletContext = s.getServletContext();
                s.invalidate();
                return servletContext;
            }
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
