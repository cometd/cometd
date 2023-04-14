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

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.cometd.server.spi.CometDCookie;
import org.cometd.server.spi.CometDInput;
import org.cometd.server.spi.CometDRequest;

class ServletCometDRequest implements CometDRequest {
    private final HttpServletRequest request;
    private ServletCometDInput input;

    ServletCometDRequest(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getCharacterEncoding() {
        return request.getCharacterEncoding();
    }

    @Override
    public String getContentType() {
        return request.getContentType();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        request.setCharacterEncoding(encoding);
    }

    @Override
    public CometDCookie[] getCookies() {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        CometDCookie[] result = new CometDCookie[cookies.length];
        for (int i = 0; i < cookies.length; i++) {
            result[i] = new CometDCookie(cookies[i].getName(), cookies[i].getValue());
        }
        return result;
    }

    @Override
    public String getParameter(String name) {
        return request.getParameter(name);
    }

    @Override
    public String[] getParameterValues(String name) {
        return request.getParameterValues(name);
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public CometDInput getInput() {
        if (input == null) {
            try {
                input = new ServletCometDInput(request);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return input;
    }

    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
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
        if (HttpServletRequest.class.isAssignableFrom(clazz))
            return (T)request;
        return null;
    }
}
