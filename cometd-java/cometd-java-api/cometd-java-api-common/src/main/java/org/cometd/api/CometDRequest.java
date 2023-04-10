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
package org.cometd.api;

import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Locale;

public interface CometDRequest
{
    // HTTP metadata
    String getCharacterEncoding();
    String getContentType();

    void setCharacterEncoding(String encoding) throws UnsupportedEncodingException;
    CometDCookie[] getCookies();
    String getHeader(String name);
    Enumeration<String> getHeaders(String name);
    String getParameter(String name);
    String[] getParameterValues(String name);
    String getQueryString();
    StringBuffer getRequestURL();
    String getMethod();
    Enumeration<Locale> getLocales();

    // input reading
    CometDInput getInput();
    Reader getReader();

    // network/transport layer metadata
    String getProtocol();
    String getLocalName();
    int getLocalPort();
    String getRemoteHost();
    int getRemotePort();
    boolean isSecure();

    // security stuff, remove?
    Principal getUserPrincipal();
    boolean isUserInRole(String role);

    // in-memory data
    Object getAttribute(String name);
    void setAttribute(String name, Object value);

    <T> T unwrap(Class<T> clazz);
}
