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
import java.nio.charset.Charset;
import java.util.List;

import org.cometd.server.CometDRequest;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;

class JettyCometDRequest implements CometDRequest {
    private final Request request;
    private Input cometDInput;

    public JettyCometDRequest(Request request) {
        this.request = request;
    }

    @Override
    public String getCharacterEncoding() {
        Charset charset = Request.getCharset(request);
        return charset == null ? "ISO-8859-1" : charset.name();
    }

    @Override
    public String getCookie(String name) {
        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies == null) {
            return null;
        }
        for (HttpCookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Override
    public List<String> getParameterValues(String name) {
        Fields fields = Request.extractQueryParameters(request);
        return fields.getValuesOrEmpty(name);
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
        public Input.Chunk read() throws IOException {
            Content.Chunk chunk = request.request.read();
            if (chunk == null) {
                return null;
            }
            if (Content.Chunk.isFailure(chunk)) {
                throw IO.rethrow(chunk.getFailure());
            }
            return new Chunk(chunk);
        }

        private static class Chunk implements Input.Chunk {
            private final Content.Chunk chunk;

            private Chunk(Content.Chunk chunk) {
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

            @Override
            public String toString() {
                return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), chunk);
            }
        }
    }
}
