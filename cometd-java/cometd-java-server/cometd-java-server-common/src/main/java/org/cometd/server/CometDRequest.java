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
package org.cometd.server;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p>An abstraction over HTTP requests.</p>
 * <p>This abstraction allows CometD to be independent
 * from Servlet, or server-specific HTTP APIs.</p>
 */
public interface CometDRequest {
    /**
     * @return the HTTP method
     */
    String getMethod();

    /**
     * @return the HTTP protocol version
     */
    String getProtocol();

    /**
     * @param name the query parameter name
     * @return the values of the given query parameter
     */
    String[] getParameterValues(String name);

    /**
     * @return the charset of the request body
     */
    String getCharacterEncoding();

    /**
     * @return the value of the request cookie with the given name,
     * or {@code null} if there is no such cookie
     */
    String getCookie(String name);

    /**
     * @return the input to read the request body from
     */
    Input getInput();

    /**
     * @param name the attribute name
     * @return the value of the attribute with the given name,
     * or {@code null} if not such attribute exists
     */
    Object getAttribute(String name);

    /**
     * @param name  the attribute name
     * @param value the attribute value
     */
    void setAttribute(String name, Object value);

    /**
     * <p>The source of the request body.</p>
     */
    interface Input {
        /**
         * <p>Demands to invoke the given callback when request content bytes are available.</p>
         *
         * @param demandCallback the callback to invoke when request content bytes are available
         */
        void demand(Runnable demandCallback);

        /**
         * <p>Reads request content bytes into a {@link Chunk}.</p>
         * <p>The returned {@link Chunk} can be:</p>
         * <ul>
         * <li>{@code null}, if no request content bytes are available</li>
         * <li>a possibly empty {@link Chunk} of request content bytes</li>
         * </ul>
         *
         * @return a {@link Chunk} of request content bytes,
         * or {@code null} if no request content bytes are available
         * @throws IOException if the read fails
         */
        Chunk read() throws IOException;

        /**
         * <p>Request content bytes with the indication of whether they are the last.</p>
         */
        interface Chunk {
            /**
             * <p>A convenient {@link Chunk} constant indicating end-of-file.</p>
             */
            Chunk EOF = new Chunk() {
                private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

                @Override
                public ByteBuffer byteBuffer() {
                    return EMPTY;
                }

                @Override
                public boolean isLast() {
                    return true;
                }

                @Override
                public void release() {
                }

                @Override
                public String toString() {
                    return "%s@%x[EOF]".formatted(Chunk.class.getSimpleName(), hashCode());
                }
            };

            /**
             * @return the {@link ByteBuffer} containing the request content bytes
             */
            ByteBuffer byteBuffer();

            /**
             * @return whether this {@link Chunk} is the last
             */
            boolean isLast();

            /**
             * <p>Releases this {@link Chunk} so its {@link #byteBuffer()}
             * can be recycled.</p>
             */
            void release();
        }
    }
}
