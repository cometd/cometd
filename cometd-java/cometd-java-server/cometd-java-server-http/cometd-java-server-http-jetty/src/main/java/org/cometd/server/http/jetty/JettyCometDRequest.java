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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.cometd.server.CometDRequest;
import org.eclipse.jetty.http.CookieCache;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.IO;

class JettyCometDRequest implements CometDRequest {
    private final Request request;
    private final CookieCache cookieCache = new CookieCache();
    private Input cometDInput;

    public JettyCometDRequest(Request request) {
        this.request = request;
    }

    @Override
    public String getCharacterEncoding() {
        String[] split = request.getHeaders().get(HttpHeader.CONTENT_TYPE).split(";");
        if (split.length == 2)
            return split[1].split("=")[1];
        return "iso-8859-1";
    }

    @Override
    public List<CometDCookie> getCookies() {
        List<HttpCookie> cookies = cookieCache.getCookies(request.getHeaders());
        return cookies.stream()
            .map(httpCookie -> new CometDCookie(httpCookie.getName(), httpCookie.getValue()))
            .toList();
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
    public Input getInput() {
        if (cometDInput == null) {
            cometDInput = new JettyCometDInput(this);
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

    private static class JettyCometDInput implements Input {
        private final JettyCometDRequest request;

        private JettyCometDInput(JettyCometDRequest request) {
            this.request = request;
        }

        @Override
        public void demand(Runnable r) {
            request.request.demand(r);
        }

        @Override
        public Chunk read() throws IOException {
            Content.Chunk chunk = request.request.read();
            if (chunk == null) {
                return null;
            }
            if (Content.Chunk.isFailure(chunk)) {
                throw IO.rethrow(chunk.getFailure());
            }
            return new HandlerChunk(chunk);
        }

        private static class HandlerChunk implements Chunk {
            private final Content.Chunk chunk;

            private HandlerChunk(Content.Chunk chunk) {
                this.chunk = chunk;
            }

            @Override
            public ByteBuffer byteBuffer() {
                return chunk.getByteBuffer();
            }

            @Override
            public boolean isLast() {
                return chunk.isLast();
            }

            @Override
            public void release() {
                chunk.release();
            }
        }
    }
}
