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

class JakartaCometDResponse implements CometDResponse {
    private final HttpServletResponse response;
    private JakartaCometDOutput output;

    JakartaCometDResponse(HttpServletResponse response) {
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
                output = new JakartaCometDOutput(response);
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

    private static class JakartaCometDOutput implements Output, WriteListener {
        public static final Promise<Void> WRITE_READY = new Promise<>() {};

        private final AtomicReference<Promise<Void>> state = new AtomicReference<>();
        private final ServletOutputStream outputStream;

        private JakartaCometDOutput(HttpServletResponse response) throws IOException {
            this.outputStream = response.getOutputStream();
            this.outputStream.setWriteListener(this);
        }

        @Override
        public void onWritePossible() {
            // This method races with write().
            Promise<Void> pendingPromise = state.getAndUpdate(existing -> existing == null ? WRITE_READY : null);
            if (pendingPromise != null) {
                pendingPromise.succeed(null);
            }
        }

        @Override
        public void onError(Throwable failure) {
            // This method races with write().
            Promise<Void> pendingPromise = state.getAndUpdate(existing -> existing == null ? WRITE_READY : null);
            if (pendingPromise != null) {
                pendingPromise.fail(failure);
            }
        }

        @Override
        public void write(boolean last, byte[] bytes, Promise<Void> promise) {
            try {
                outputStream.write(bytes);
                if (outputStream.isReady()) {
                    promise.succeed(null);
                } else {
                    // In a race with onWritePossible().
                    Promise<Void> writeReady = state.getAndUpdate(existing -> existing == null ? promise : null);
                    if (writeReady != null) {
                        // Lost the race with onWritePossible(), but it
                        // is possible to write, so succeed the promise.
                        promise.succeed(null);
                    }
                }
            } catch (Throwable x) {
                promise.fail(x);
            }
        }
    }
}
