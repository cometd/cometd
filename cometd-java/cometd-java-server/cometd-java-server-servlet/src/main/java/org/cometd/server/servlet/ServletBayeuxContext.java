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

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.cometd.bayeux.server.BayeuxContext;

class ServletBayeuxContext implements BayeuxContext {
    private final HttpServletRequest request;

    ServletBayeuxContext(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public Principal getUserPrincipal() {
        return request.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return request.isUserInRole(role);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(request.getRemoteHost(), request.getRemotePort());
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(request.getLocalName(), request.getLocalPort());
    }

    @Override
    public String getHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public List<String> getHeaderValues(String name) {
        return Collections.list(request.getHeaders(name));
    }

    @Override
    public String getParameter(String name) {
        return request.getParameter(name);
    }

    @Override
    public List<String> getParameterValues(String name) {
        return List.of(request.getParameterValues(name));
    }

    @Override
    public String getCookie(String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public String getHttpSessionId() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return session.getId();
        }
        return null;
    }

    @Override
    public Object getHttpSessionAttribute(String name) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return session.getAttribute(name);
        }
        return null;
    }

    @Override
    public void setHttpSessionAttribute(String name, Object value) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(name, value);
        } else {
            throw new IllegalStateException("!session");
        }
    }

    @Override
    public void invalidateHttpSession() {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    @Override
    public Object getRequestAttribute(String name) {
        return request.getAttribute(name);
    }

    private ServletContext getServletContext() {
        HttpSession s = request.getSession(false);
        if (s != null) {
            return s.getServletContext();
        } else {
            s = request.getSession(true);
            ServletContext servletContext = s.getServletContext();
            s.invalidate();
            return servletContext;
        }
    }

    @Override
    public Object getContextAttribute(String name) {
        return getServletContext().getAttribute(name);
    }

    @Override
    public String getContextInitParameter(String name) {
        return getServletContext().getInitParameter(name);
    }

    @Override
    public String getContextPath() {
        return request.getContextPath();
    }

    @Override
    public String getURL() {
        StringBuffer url = request.getRequestURL();
        String query = request.getQueryString();
        if (query != null) {
            url.append("?").append(query);
        }
        return url.toString();
    }

    @Override
    public List<Locale> getLocales() {
        return Collections.list(request.getLocales());
    }

    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }
}