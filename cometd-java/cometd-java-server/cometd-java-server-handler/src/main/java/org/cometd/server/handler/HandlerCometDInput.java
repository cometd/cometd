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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.cometd.server.spi.CometDInput;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.IO;

class HandlerCometDInput implements CometDInput {
    private final HandlerCometDRequest request;

    HandlerCometDInput(HandlerCometDRequest request) {
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
