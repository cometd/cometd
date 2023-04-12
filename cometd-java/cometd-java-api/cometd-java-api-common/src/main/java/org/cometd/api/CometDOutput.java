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
package org.cometd.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import org.cometd.bayeux.Promise;

public interface CometDOutput {
    @Deprecated
    default void write(char c) throws IOException {
        write(String.valueOf(c).getBytes(StandardCharsets.US_ASCII));
    }

    @Deprecated
    default void write(char c, Promise<Void> promise) {
        write(String.valueOf(c).getBytes(StandardCharsets.US_ASCII), promise);
    }

    default void write(byte[] jsonBytes) throws IOException {
        Promise.Completable<Void> promise = new Promise.Completable<>();
        write(jsonBytes, promise);
        try {
            promise.get();
        } catch (InterruptedException | ExecutionException e) {
            if (e.getCause() instanceof IOException)
                throw (IOException)e.getCause();
            throw new IOException(e.getCause());
        }
    }

    void write(byte[] jsonBytes, Promise<Void> promise);

    void close() throws IOException;
}
