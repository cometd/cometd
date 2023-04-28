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

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;

import org.cometd.server.spi.CometDInput;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.SerializedInvoker;

class HandlerCometDInput implements CometDInput {
    private final SerializedInvoker serializedInvoker = new SerializedInvoker();
    private final HandlerCometDRequest handlerCometDRequest;
    private Content.Chunk currentChunk;
    private Reader reader;

    HandlerCometDInput(HandlerCometDRequest handlerCometDRequest) {
        this.handlerCometDRequest = handlerCometDRequest;
    }

    @Override
    public Reader asReader() {
        if (reader == null) {
            String characterEncoding = handlerCometDRequest.getCharacterEncoding();
            reader = new InputStreamReader(Content.Source.asInputStream(handlerCometDRequest.request), Charset.forName(characterEncoding));
        }
        return reader;
    }

    @Override
    public void demand(Runnable r) {
        if (currentChunk != null) {
            serializedInvoker.run(r);
        } else {
            handlerCometDRequest.request.demand(r);
        }
    }

    @Override
    public int read(byte[] buffer, int off, int len) {
        if (currentChunk == null) {
            currentChunk = handlerCometDRequest.request.read();
            if (currentChunk == null) {
                return 0;
            }
            if (currentChunk.isLast() && !currentChunk.hasRemaining()) {
                return -1;
            }
        }
        int read = currentChunk.get(buffer, off, len);
        if (!currentChunk.hasRemaining()) {
            currentChunk.release();
            currentChunk = currentChunk.isLast() ? Content.Chunk.EOF : null;
        }
        return read;
    }
}
