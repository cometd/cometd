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
package org.cometd.server.spi;

import java.io.UnsupportedEncodingException;

public interface CometDRequest
{
    // HTTP metadata
    String getContentType();
    String getCharacterEncoding();
    void setCharacterEncoding(String encoding) throws UnsupportedEncodingException;
    CometDCookie[] getCookies();
    String getParameter(String name);
    String[] getParameterValues(String name);
    String getMethod();
    String getProtocol();

    // input reading
    CometDInput getInput();

    // network/transport layer metadata
    boolean isSecure();

    // in-memory data
    Object getAttribute(String name);
    void setAttribute(String name, Object value);

    // underlying impl
    <T> T unwrap(Class<T> clazz);
}
