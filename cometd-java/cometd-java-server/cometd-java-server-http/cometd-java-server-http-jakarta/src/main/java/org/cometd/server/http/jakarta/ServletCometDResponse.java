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
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Promise;
import org.cometd.server.CometDResponse;

class ServletCometDResponse implements CometDResponse {
    private final HttpServletResponse response;
    private ServletCometDOutput output;

    ServletCometDResponse(HttpServletResponse response) {
        this.response = response;
    }

    @Override
    public void addHeader(String name, String value) {
        response.addHeader(name, value);
    }

    @Override
    public Output getOutput() {
        if (output == null) {
            try {
                output = new ServletCometDOutput(response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return output;
    }

    @Override
    public void setContentType(String contentType) {
        response.setContentType(contentType);
    }

    private static class ServletCometDOutput implements Output, WriteListener {
        private final ServletOutputStream outputStream;
        private final AtomicReference<NextWrite> nextWriteRef = new AtomicReference<>();

        private ServletCometDOutput(HttpServletResponse response) throws IOException {
            this.outputStream = response.getOutputStream();
            this.outputStream.setWriteListener(this);
        }

        @Override
        public void onWritePossible() {
            NextWrite nextWrite = nextWriteRef.getAndSet(null);
            if (nextWrite != null)
                nextWrite.write(outputStream);
        }

        @Override
        public void onError(Throwable t) {
            NextWrite nextWrite = nextWriteRef.getAndSet(null);
            if (nextWrite != null)
                nextWrite.fail(t);
        }

        @Override
        public void write(boolean last, byte[] bytes, Promise<Void> promise) {
            if (outputStream.isReady()) {
                try {
                    outputStream.write(bytes);
                    promise.succeed(null);
                } catch (IOException e) {
                    promise.fail(e);
                }
            } else {
                if (!nextWriteRef.compareAndSet(null, new NextWrite(bytes, promise))) {
                    throw new IllegalStateException("Write pending");
                }
            }
        }

        private record NextWrite(byte[] jsonBytes, Promise<Void> promise) {
            public void fail(Throwable t) {
                promise.fail(t);
            }

            void write(ServletOutputStream servletOutputStream) {
                try {
                    servletOutputStream.write(jsonBytes);
                    promise.succeed(null);
                } catch (Throwable x) {
                    promise.fail(x);
                }
            }
        }
    }
}
