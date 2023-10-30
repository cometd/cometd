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
package org.cometd.server.http.jakarta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.cometd.server.CometDRequest;
import org.eclipse.jetty.util.IO;

class JakartaCometDRequest implements CometDRequest {
    private final HttpServletRequest request;
    private JakartaCometDInput input;

    JakartaCometDRequest(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getCharacterEncoding() {
        return request.getCharacterEncoding();
    }

    @Override
    public String getCookie(String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
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
    public Input getInput() {
        if (input == null) {
            try {
                input = new JakartaCometDInput(request);
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
    public Object getAttribute(String name) {
        return request.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        request.setAttribute(name, value);
    }

    private static class JakartaCometDInput implements Input, ReadListener {
        private static final Runnable READ_READY = () -> {};

        private final ServletInputStream inputStream;
        private final AtomicReference<Runnable> state = new AtomicReference<>();
        private volatile Throwable failure;

        private JakartaCometDInput(HttpServletRequest request) throws IOException {
            this.inputStream = request.getInputStream();
            if ("POST".equals(request.getMethod())) {
                this.inputStream.setReadListener(this);
            }
        }

        @Override
        public void demand(Runnable demandCallback) {
            // This method races with onDataAvailable() and onAllDataRead().
            Runnable readReady = state.getAndUpdate(existing -> existing == null ? demandCallback : null);
            if (readReady != null) {
                // Lost the race with onDataAvailable(), but there
                // is data available, so run the demandCallback.
                demandCallback.run();
            }
        }

        @Override
        public Input.Chunk read() throws IOException {
            if (failure != null) {
                throw IO.rethrow(failure);
            } else if (inputStream.isFinished()) {
                return Input.Chunk.EOF;
            } else if (inputStream.isReady()) {
                // TODO: the chunks can be pooled.
                Input.Chunk chunk = new Chunk();
                ByteBuffer byteBuffer = chunk.byteBuffer();
                int read = inputStream.read(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining());
                if (read < 0) {
                    chunk.release();
                    return Input.Chunk.EOF;
                } else if (read == 0) {
                    chunk.release();
                    return null;
                } else {
                    byteBuffer.limit(read);
                    return chunk;
                }
            } else {
                return null;
            }
        }

        @Override
        public void onDataAvailable() {
            // This method races with demand(Runnable).
            Runnable readCallback = state.getAndUpdate(existing -> existing == null ? READ_READY : null);
            if (readCallback != null) {
                readCallback.run();
            }
        }

        @Override
        public void onAllDataRead() {
            onDataAvailable();
        }

        @Override
        public void onError(Throwable failure) {
            this.failure = failure;
            onDataAvailable();
        }

        private static class Chunk implements Input.Chunk {
            private final ByteBuffer byteBuffer = ByteBuffer.allocate(512);

            @Override
            public ByteBuffer byteBuffer() {
                return byteBuffer;
            }

            @Override
            public boolean isLast() {
                return false;
            }

            @Override
            public void release() {
            }

            @Override
            public String toString() {
                return "%s@%x[last=%b,%s]".formatted(getClass().getSimpleName(), hashCode(), isLast(), byteBuffer());
            }
        }
    }
}
