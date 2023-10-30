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
package org.cometd.server.http.jetty;

import java.net.SocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Locale;

import org.cometd.bayeux.server.BayeuxContext;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;

class JettyBayeuxContext implements BayeuxContext {
    private final Request request;

    JettyBayeuxContext(Request request) {
        this.request = request;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return request.getConnectionMetaData().getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return request.getConnectionMetaData().getLocalSocketAddress();
    }

    @Override
    public String getHeader(String name) {
        return request.getHeaders().get(name);
    }

    @Override
    public List<String> getHeaderValues(String name) {
        return request.getHeaders().getValuesList(name);
    }

    @Override
    public String getParameter(String name) {
        return Request.extractQueryParameters(request).getValue(name);
    }

    @Override
    public List<String> getParameterValues(String name) {
        return Request.extractQueryParameters(request).getValues(name);
    }

    @Override
    public String getCookie(String name) {
        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies != null) {
            for (HttpCookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public Object getContextAttribute(String name) {
        return request.getContext().getAttribute(name);
    }

    @Override
    public Object getRequestAttribute(String name) {
        return request.getAttribute(name);
    }

    @Override
    public Object getSessionAttribute(String name) {
        Session session = request.getSession(false);
        return session == null ? null : session.getAttribute(name);
    }

    @Override
    public String getContextPath() {
        return request.getContext().getContextPath();
    }

    @Override
    public String getURL() {
        return request.getHttpURI().asString();
    }

    @Override
    public List<Locale> getLocales() {
        return Request.getLocales(request);
    }

    @Override
    public String getProtocol() {
        return request.getConnectionMetaData().getProtocol();
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }
}
