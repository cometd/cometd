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
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.cometd.server.spi.CometDOutput;
import org.cometd.bayeux.Promise;

class ServletCometDOutput implements CometDOutput, WriteListener {
    private final ServletOutputStream outputStream;
    private final AtomicReference<NextWrite> nextWriteRef = new AtomicReference<>();

    ServletCometDOutput(HttpServletResponse response) throws IOException {
        this.outputStream = response.getOutputStream();
        this.outputStream.setWriteListener(this);
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
        NextWrite nextWrite = nextWriteRef.get();
        if (nextWrite != null)
            throw new IOException("Closed stream with pending write: " + nextWrite);
    }

    @Override
    public void onWritePossible() {
        nextWriteRef.getAndSet(null).write(outputStream);
    }

    @Override
    public void onError(Throwable t) {
        nextWriteRef.getAndSet(null).fail(t);
    }

    @Override
    public void write(byte[] jsonBytes, Promise<Void> promise) {
        if (outputStream.isReady()) {
            try {
                outputStream.write(jsonBytes);
                promise.succeed(null);
            } catch (IOException e) {
                promise.fail(e);
            }
        } else {
            if (!nextWriteRef.compareAndSet(null, new NextWrite(jsonBytes, promise))) {
                throw new IllegalStateException("Write pending");
            }
        }
    }

    @Override
    public void write(byte jsonByte, Promise<Void> promise) {
        if (outputStream.isReady()) {
            try {
                outputStream.write(jsonByte);
                promise.succeed(null);
            } catch (IOException e) {
                promise.fail(e);
            }
        } else {
            if (!nextWriteRef.compareAndSet(null, new NextWrite(new byte[]{jsonByte}, promise))) {
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
