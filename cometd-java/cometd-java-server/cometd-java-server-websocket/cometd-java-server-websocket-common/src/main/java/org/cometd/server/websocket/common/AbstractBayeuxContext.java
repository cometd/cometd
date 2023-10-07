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
package org.cometd.server.websocket.common;

import java.net.HttpCookie;
import java.net.SocketAddress;
import java.security.Principal;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.cometd.bayeux.server.BayeuxContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractBayeuxContext implements BayeuxContext {
    private static final Logger logger = LoggerFactory.getLogger(BayeuxContext.class);

    private final String url;
    private final String contextPath;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> parameters;
    private final Principal principal;
    private final SocketAddress localAddress;
    private final SocketAddress remoteAddress;
    private final List<Locale> locales;
    private final String protocol;
    private final boolean secure;

    public AbstractBayeuxContext(String uri, String contextPath, String query, Map<String, List<String>> headers, Map<String, List<String>> parameters, Principal principal, SocketAddress local, SocketAddress remote, List<Locale> locales, String protocol, boolean secure) {
        this.url = uri + (query == null ? "" : "?" + query);
        this.contextPath = contextPath;
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        this.headers.putAll(headers);
        this.parameters = parameters;
        this.principal = principal;
        this.localAddress = local;
        this.remoteAddress = remote;
        this.locales = locales;
        this.protocol = protocol;
        this.secure = secure;
    }

    @Override
    public String getURL() {
        return url;
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() > 0 ? values.get(0) : null;
    }

    @Override
    public List<String> getHeaderValues(String name) {
        return headers.get(name);
    }

    @Override
    public String getParameter(String name) {
        List<String> values = parameters.get(name);
        return values != null && values.size() > 0 ? values.get(0) : null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        return parameters.get(name);
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public List<Locale> getLocales() {
        return locales;
    }

    @Override
    public String getCookie(String name) {
        try {
            List<String> values = headers.get("Cookie");
            if (values != null) {
                for (String value : values) {
                    for (HttpCookie cookie : CookieParser.parse(value)) {
                        if (cookie.getName().equals(name)) {
                            return cookie.getValue();
                        }
                    }
                }
            }
            return null;
        } catch (ParseException x) {
            logger.debug("Error parsing cookie " + x.getMessage() + " at index " + x.getErrorOffset(), x);
            return null;
        }
    }

    @Override
    public Object getContextAttribute(String name) {
        return null;
    }

    @Override
    public Object getRequestAttribute(String name) {
        return null;
    }

    @Override
    public Object getSessionAttribute(String name) {
        return null;
    }

    @Override
    public String getContextInitParameter(String name) {
        return null;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }
}
