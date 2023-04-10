/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.servlet;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import org.cometd.api.CometDCookie;
import org.cometd.api.CometDInput;
import org.cometd.api.CometDRequest;

class ServletCometDRequest implements CometDRequest {
    private final HttpServletRequest request;

    public ServletCometDRequest(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getCharacterEncoding()
    {
        return request.getCharacterEncoding();
    }

    @Override
    public String getContentType()
    {
        return request.getContentType();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException
    {
        request.setCharacterEncoding(encoding);
    }

    @Override
    public CometDCookie[] getCookies()
    {
        return new CometDCookie[0];
    }

    @Override
    public String getHeader(String name)
    {
        return request.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name)
    {
        return request.getHeaders(name);
    }

    @Override
    public String getParameter(String name)
    {
        return request.getParameter(name);
    }

    @Override
    public String[] getParameterValues(String name)
    {
        return request.getParameterValues(name);
    }

    @Override
    public String getQueryString()
    {
        return request.getQueryString();
    }

    @Override
    public StringBuffer getRequestURL()
    {
        return request.getRequestURL();
    }

    @Override
    public String getMethod()
    {
        return request.getMethod();
    }

    @Override
    public Enumeration<Locale> getLocales()
    {
        return request.getLocales();
    }

    @Override
    public CometDInput getInput()
    {
        try
        {
            return new ServletCometDInput(request);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Reader getReader()
    {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public String getProtocol()
    {
        return request.getProtocol();
    }

    @Override
    public String getLocalName()
    {
        return request.getLocalName();
    }

    @Override
    public int getLocalPort()
    {
        return request.getLocalPort();
    }

    @Override
    public String getRemoteHost()
    {
        return request.getRemoteHost();
    }

    @Override
    public int getRemotePort()
    {
        return request.getRemotePort();
    }

    @Override
    public boolean isSecure()
    {
        return request.isSecure();
    }

    @Override
    public Principal getUserPrincipal()
    {
        return request.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return request.isUserInRole(role);
    }

    @Override
    public Object getAttribute(String name)
    {
        return request.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        request.setAttribute(name, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> clazz)
    {
        if (HttpServletRequest.class.isAssignableFrom(clazz))
            return (T)request;
        return null;
    }
}
