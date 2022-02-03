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
package org.cometd.common;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.List;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.Utf8StringBuilder;

/**
 * <p>A {@link JSONContext.AsyncParser} that parses an array of Bayeux messages.</p>
 * <p>This is not a generic parser that can parse any JSON.</p>
 * <p>This parser buffers all input bytes in memory, and performs blocking JSON parsing
 * via {@link JSONContext#parse(String)} only when {@link #complete()} is called.</p>
 */
public class BufferingJSONAsyncParser implements JSONContext.AsyncParser {
    private final Utf8StringBuilder buffer = new Utf8StringBuilder();
    private final JSONContext<? extends Message.Mutable> jsonContext;

    public BufferingJSONAsyncParser(JSONContext<? extends Message.Mutable> jsonContext) {
        this.jsonContext = jsonContext;
    }

    @Override
    public void parse(byte[] bytes, int offset, int length) {
        buffer.append(bytes, offset, length);
    }

    @Override
    public void parse(ByteBuffer byteBuffer) {
        buffer.append(byteBuffer);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R complete() {
        try {
            String json = buffer.toString();
            buffer.reset();
            Message.Mutable[] result = jsonContext.parse(json);
            return (R)List.of(result);
        } catch (ParseException x) {
            throw new IllegalArgumentException(x);
        }
    }
}
