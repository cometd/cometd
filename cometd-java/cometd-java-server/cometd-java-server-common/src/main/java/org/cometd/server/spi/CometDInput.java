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
package org.cometd.server.spi;

import java.io.IOException;
import java.io.Reader;

public interface CometDInput
{
    Reader asReader(); // TODO remove API? This requires abandoning blocking transports

    void demand(Runnable r);

    // TODO: the APis must be based on ByteBuffer to make them very efficient with Jetty 12 Chunks
    //  byte[] can be wrapped into ByteBuffers without copy, but not the other way around.

    default int read(byte[] buffer) throws IOException
    {
        return read(buffer, 0, buffer.length);
    }

    int read(byte[] buffer, int off, int len) throws IOException;
}