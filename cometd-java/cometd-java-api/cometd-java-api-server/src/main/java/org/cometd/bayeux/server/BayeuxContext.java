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
package org.cometd.bayeux.server;

import java.net.SocketAddress;
import java.security.Principal;
import java.util.List;
import java.util.Locale;

/**
 * <p>The Bayeux Context provides information about the current context of a Bayeux message.</p>
 * <p>This information may be from an associated HTTP request, or from a HTTP request originally
 * used to establish the connection to the server (for example in a WebSocket upgrade).</p>
 */
public interface BayeuxContext {
    /**
     * @return the user {@link Principal} (if any)
     */
    Principal getUserPrincipal();

    /**
     * @param role the role to check whether the user belongs to
     * @return true if there is a known user and they are in the given role
     */
    boolean isUserInRole(String role);

    /**
     * @return the remote socket address
     */
    SocketAddress getRemoteAddress();

    /**
     * @return the local socket address
     */
    SocketAddress getLocalAddress();

    /**
     * @param name the name of the request header
     * @return the value of the header, or {@code null} if there is no such header
     */
    String getHeader(String name);

    /**
     * @param name the name of the request header
     * @return the values of the header, or {@code null} if no such header
     */
    List<String> getHeaderValues(String name);

    /**
     * @param name the name of the query parameter
     * @return the value of the query parameter, or {@code null} if no such parameter
     */
    String getParameter(String name);

    /**
     * @param name the name of the query parameter
     * @return the values of the query parameter, or {@code null} if no such parameter
     */
    List<String> getParameterValues(String name);

    /**
     * @param name the name of the cookie
     * @return the value of the cookie value, or {@code null} if no such cookie
     */
    String getCookie(String name);

    /**
     * @param name the context attribute name
     * @return the context attribute value, or {@code null} if no such attribute
     */
    Object getContextAttribute(String name);

    /**
     * @param name the request attribute name
     * @return the request attribute value, or {@code null} if no such attribute
     */
    Object getRequestAttribute(String name);

    /**
     * <p>Returns an HTTP session attribute value.</p>
     * <p>{@link ServerSession#getAttribute(String)} should be used to retrieve
     * attribute values in session scope.</p>
     *
     * @param name the HTTP session attribute name
     * @return the HTTP session attribute value, or {@code null} if no such attribute
     */
    Object getSessionAttribute(String name);

    /**
     * @return the web application context path
     */
    String getContextPath();

    /**
     * @return the full request URI complete with query string if present
     */
    String getURL();

    /**
     * @return the request {@link Locale}s, in order of preference, or the default
     * server {@link Locale} if the request does not specify locales
     */
    List<Locale> getLocales();

    /**
     * @return a string containing the protocol name and version number
     */
    String getProtocol();

    /**
     * @return whether the request was made over a secure transport
     */
    boolean isSecure();
}
