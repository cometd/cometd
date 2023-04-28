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
import java.util.concurrent.CountDownLatch;

import org.cometd.bayeux.Promise;
import org.cometd.server.spi.CometDOutput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

class HandlerCometDOutput implements CometDOutput {
    private final Response response;

    HandlerCometDOutput(Response response) {
        this.response = response;
    }

    @Override
    public void write(byte jsonByte, Promise<Void> promise) {
        write(new byte[] {jsonByte}, promise);
    }

    @Override
    public void write(byte[] jsonBytes, Promise<Void> promise) {
        response.write(false, ByteBuffer.wrap(jsonBytes), new Callback() {
            @Override
            public void succeeded() {
                promise.succeed(null);
            }

            @Override
            public void failed(Throwable x) {
                promise.fail(x);
            }
        });
    }

    @Override
    public void close() throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        var callback = new Callback() {
            Throwable failure = null;
            @Override
            public void succeeded() {
                latch.countDown();
            }

            @Override
            public void failed(Throwable x) {
                failure = x;
                latch.countDown();
            }
        };
        response.write(true, BufferUtil.EMPTY_BUFFER, callback);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        if (callback.failure != null) {
            throw new IOException(callback.failure);
        }
    }
}
