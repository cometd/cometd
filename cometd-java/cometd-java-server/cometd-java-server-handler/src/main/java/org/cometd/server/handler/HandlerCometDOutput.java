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

import java.nio.ByteBuffer;

import org.cometd.bayeux.Promise;
import org.cometd.server.spi.CometDOutput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

class HandlerCometDOutput implements CometDOutput {
    private final Response response;

    HandlerCometDOutput(Response response) {
        this.response = response;
    }

    @Override
    public void write(boolean last, byte[] bytes, Promise<Void> promise) {
        response.write(last, ByteBuffer.wrap(bytes), new Callback() {
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
}
