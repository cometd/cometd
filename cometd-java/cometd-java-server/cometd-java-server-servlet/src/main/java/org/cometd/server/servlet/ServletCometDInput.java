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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.cometd.server.spi.CometDInput;
import org.eclipse.jetty.util.thread.SerializedInvoker;

class ServletCometDInput implements CometDInput, ReadListener {
    private final ServletInputStream inputStream;
    private final HttpServletRequest request;
    private final SerializedInvoker serializedInvoker = new SerializedInvoker();
    private final AtomicReference<NextRead> nextReadRef = new AtomicReference<>();
    private volatile Throwable error;

    ServletCometDInput(HttpServletRequest request) throws IOException {
        this.request = request;
        this.inputStream = request.getInputStream();
        this.inputStream.setReadListener(this);
    }

    @Override
    public Reader asReader() {
        Charset charset = Charset.forName(request.getCharacterEncoding());
        return new InputStreamReader(new InputStream() {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return ServletCometDInput.this.read(b, off, len);
            }

            @Override
            public int read() throws IOException {
                return ServletCometDInput.this.read();
            }
        }, charset);
    }

    @Override
    public void demand(Runnable r) {
        if (error != null || inputStream.isFinished() || inputStream.isReady()) {
            serializedInvoker.run(r);
        } else {
            nextReadRef.set(new NextRead(r));
        }
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (error != null) {
            throw errorToIOException();
        } else if (inputStream.isFinished()) {
            return -1;
        } else if (inputStream.isReady()) {
            return inputStream.read(buffer, off, len);
        } else {
            return 0;
        }
    }

    private int read() throws IOException
    {
        if (error != null) {
            throw errorToIOException();
        } else if (inputStream.isFinished()) {
            return -1;
        } else if (inputStream.isReady()) {
            return inputStream.read();
        } else {
            return 0;
        }
    }

    private IOException errorToIOException() {
        if (error instanceof IOException) {
            return (IOException)error;
        } else {
            return new IOException(error);
        }
    }

    @Override
    public void onDataAvailable() {
        NextRead nextRead = nextReadRef.getAndSet(null);
        if (nextRead != null) {
            nextRead.run();
        }
    }

    @Override
    public void onAllDataRead() {
        NextRead nextRead = nextReadRef.getAndSet(null);
        if (nextRead != null) {
            nextRead.run();
        }
    }

    @Override
    public void onError(Throwable t) {
        error = t;
        NextRead nextRead = nextReadRef.getAndSet(null);
        if (nextRead != null) {
            nextRead.run();
        }
    }

    private record NextRead(Runnable runnable) {
        public void run() {
            runnable.run();
        }
    }
}
