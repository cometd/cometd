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

import org.cometd.bayeux.Promise;

/**
 * <p>An abstraction over HTTP responses.</p>
 * <p>This abstraction allows CometD to be independent
 * from Servlet, or server-specific HTTP APIs.</p>
 */
public interface CometDResponse {
    /**
     * <p>Adds an HTTP header to this response.</p>
     *
     * @param name  the HTTP header name
     * @param value the HTTP header value
     */
    void addHeader(String name, String value);

    /**
     * @param contentType the content type of the response body
     */
    void setContentType(String contentType);

    /**
     * @return the sink to write the response body to
     */
    Output getOutput();

    /**
     * <p>The sink of the response body.</p>
     */
    interface Output {
        /**
         * <p>Writes the given response bytes, notifying the
         * given promise when the write operation is complete.</p>
         *
         * @param last whether the response bytes to write are the last
         * @param bytes the response bytes to write
         * @param promise the promise to notify when the write operation is complete
         */
        void write(boolean last, byte[] bytes, Promise<Void> promise);
    }
}
