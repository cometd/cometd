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
package org.cometd.server.handler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Locale;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.server.spi.CometDRequest;
import org.eclipse.jetty.server.Request;

class HandlerBayeuxContext implements BayeuxContext {
    private final HandlerCometDRequest cometDRequest;
    private final Request request;

    HandlerBayeuxContext(HandlerCometDRequest cometDRequest, Request request) {
        this.cometDRequest = cometDRequest;
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
    public InetSocketAddress getRemoteAddress() {
        SocketAddress socketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress;
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        SocketAddress socketAddress = request.getConnectionMetaData().getLocalSocketAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress;
        return null;
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
        throw new UnsupportedOperationException("REMOVE API?");
    }

    @Override
    public List<String> getParameterValues(String name) {
        throw new UnsupportedOperationException("REMOVE API?");
    }

    @Override
    public String getCookie(String name) {
        return cometDRequest.getCookies().stream()
            .filter(cometDCookie -> cometDCookie.name().equals(name))
            .map(CometDRequest.CometDCookie::value)
            .findFirst()
            .orElse(null);
    }

    @Override
    public String getHttpSessionId() {
        return null;
    }

    @Override
    public Object getHttpSessionAttribute(String name) {
        return null;
    }

    @Override
    public void setHttpSessionAttribute(String name, Object value) {
    }

    @Override
    public void invalidateHttpSession() {
    }

    @Override
    public Object getRequestAttribute(String name) {
        return request.getAttribute(name);
    }

    @Override
    public Object getContextAttribute(String name) {
        return request.getContext().getAttribute(name);
    }

    @Override
    public String getContextInitParameter(String name) {
        throw new UnsupportedOperationException("REMOVE API?");
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
        throw new UnsupportedOperationException("REMOVE API?");
    }

    @Override
    public String getProtocol() {
        return request.getConnectionMetaData().getProtocol();
    }

    @Override
    public boolean isSecure() {
        return request.getConnectionMetaData().isSecure();
    }
}