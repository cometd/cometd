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

import java.util.List;

import org.cometd.server.spi.CometDInput;
import org.cometd.server.spi.CometDRequest;
import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HandlerCometDRequest implements CometDRequest {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerCometDRequest.class);

    final Request request;
    private final CookieCache cookieCache = new CookieCache();
    private CometDInput cometDInput;

    public HandlerCometDRequest(Request request) {
        this.request = request;
    }

    @Override
    public String getContentType() {
        return request.getHeaders().get(HttpHeader.CONTENT_TYPE);
    }

    @Override
    public String getCharacterEncoding() {
        String[] split = request.getHeaders().get(HttpHeader.CONTENT_TYPE).split(";");
        if (split.length == 2)
            return split[1].split("=")[1];
        return "iso-8859-1";
    }

    @Override
    public void setCharacterEncoding(String encoding) {
        LOGGER.warn("ignored; REMOVE API?");
    }

    @Override
    public List<CometDCookie> getCookies() {
        List<HttpCookie> cookies = cookieCache.getCookies(request.getHeaders());
        return cookies.stream()
            .map(httpCookie -> new CometDCookie(httpCookie.getName(), httpCookie.getValue()))
            .toList();
    }

    @Override
    public String getParameter(String name) {
        throw new UnsupportedOperationException("REMOVE API?");
    }

    @Override
    public String[] getParameterValues(String name) {
        throw new UnsupportedOperationException("REMOVE API?");
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public String getProtocol() {
        return request.getConnectionMetaData().getProtocol();
    }

    @Override
    public CometDInput getInput() {
        if (cometDInput == null) {
            cometDInput = new HandlerCometDInput(this);
        }
        return cometDInput;
    }

    @Override
    public Object getAttribute(String name) {
        return request.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        request.setAttribute(name, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> clazz) {
        if (Request.class.isAssignableFrom(clazz))
            return (T)request;
        return null;
    }
}
