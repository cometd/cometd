/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.bayeux.server;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Locale;

/**
 * <p>The Bayeux Context provides information about the current context of a Bayeux message.</p>
 * <p>This information may be from an associated HTTP request, or a HTTP request used to
 * originally establish the connection (for example in a websocket handshake).</p>
 */
public interface BayeuxContext {
    /**
     * @return The user Principal (if any)
     */
    Principal getUserPrincipal();

    /**
     * @param role the role to check whether the user belongs to
     * @return true if there is a known user and they are in the given role.
     */
    boolean isUserInRole(String role);

    /**
     * @return the remote socket address
     */
    InetSocketAddress getRemoteAddress();

    /**
     * @return the local socket address
     */
    InetSocketAddress getLocalAddress();

    /**
     * Get a transport header.<p>
     * Get a header for any current transport mechanism (eg HTTP request).
     * For transports like websocket, the header may be from the initial handshake.
     *
     * @param name The name of the header
     * @return The header value or null if no current transport mechanism or no such header.
     */
    String getHeader(String name);

    /**
     * Get a multi valued transport header.<p>
     * Get a header for any current transport mechanism (eg HTTP request).
     * For transports like websocket, the header may be from the initial handshake.
     *
     * @param name The name of the header
     * @return The header value or null if no current transport mechanism or no such header.
     */
    List<String> getHeaderValues(String name);

    /**
     * Get a transport parameter.<p>
     * Get a parameter for any current transport mechanism (eg HTTP request).
     * For transports like websocket, the parameter may be from the initial handshake.
     *
     * @param name The name of the parameter
     * @return The parameter value or null if no current transport mechanism or no such parameter.
     */
    String getParameter(String name);

    /**
     * Get a multi valued transport parameter.<p>
     * Get a parameter for any current transport mechanism (eg HTTP request).
     * For transports like websocket, the parameter may be from the initial handshake.
     *
     * @param name The name of the parameter
     * @return The parameter value or null if no current transport mechanism or no such parameter.
     */
    List<String> getParameterValues(String name);

    /**
     * Get a transport cookie.<p>
     * Get a cookie for any current transport mechanism (eg HTTP request).
     * For transports like websocket, the cookie may be from the initial handshake.
     *
     * @param name The name of the cookie
     * @return The cookie value or null if no current transport mechanism or no such cookie.
     */
    String getCookie(String name);

    /**
     * Access the HTTP Session (if any) ID.
     * The {@link ServerSession#getId()} should be used in preference to the HTTP Session.
     *
     * @return HTTP session ID or null
     */
    String getHttpSessionId();

    /**
     * Access the HTTP Session (if any) attributes.
     * The {@link ServerSession#getAttribute(String)} should be used in preference to the HTTP Session.
     *
     * @param name the attribute name
     * @return The attribute value
     */
    Object getHttpSessionAttribute(String name);

    /**
     * Access the HTTP Session (if any) attributes.
     * The {@link ServerSession#setAttribute(String, Object)} should be used in preference to the HTTP Session.
     *
     * @param name  the attribute name
     * @param value the attribute value
     */
    void setHttpSessionAttribute(String name, Object value);

    /**
     * Invalidate the HTTP Session.
     * The {@link ServerSession#getId()} should be used in preference to the HTTP Session.
     */
    void invalidateHttpSession();

    /**
     * Access the Request (if any) attributes.
     *
     * @param name the attribute name
     * @return The attribute value
     */
    Object getRequestAttribute(String name);

    /**
     * Access the ServletContext (if any) attributes.
     *
     * @param name the attribute name
     * @return The attribute value
     */
    Object getContextAttribute(String name);

    /**
     * Access the ServletContext (if any) init parameter.
     *
     * @param name the init parameter name
     * @return The attribute value
     */
    String getContextInitParameter(String name);

    /**
     * @return the full request URI complete with query string if present.
     */
    String getURL();

    /**
     * @return the request Locales, in order of preference, or the default
     * server Locale if the request Locales are missing.
     */
    List<Locale> getLocales();
}
