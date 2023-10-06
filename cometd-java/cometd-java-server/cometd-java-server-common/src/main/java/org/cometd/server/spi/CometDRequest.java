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
import java.util.List;

public interface CometDRequest
{
    // HTTP metadata
    String getContentType(); // TODO only needed by blocking transport
    String getCharacterEncoding(); // TODO change API so that it sets the encoding to UTF8 when none is present?
    void setCharacterEncoding(String encoding) throws UnsupportedEncodingException; // TODO only needed to set the encoding to UTF8 when getCharacterEncoding() returns null
    List<CometDCookie> getCookies();
    String[] getParameterValues(String name); // TODO only needed by blocking transport
    String getMethod();
    String getProtocol();

    // input reading
    CometDInput getInput();

    // in-memory data
    Object getAttribute(String name);
    void setAttribute(String name, Object value);

    // underlying impl
    // TODO: this is only necessary to retrieve AsyncContext - store it as attribute.
    <T> T unwrap(Class<T> clazz);

    record CometDCookie(String name, String value) {
    }
}
