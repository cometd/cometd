/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import com.fasterxml.jackson.core.async.NonBlockingInputFeeder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.cometd.bayeux.Message;

public abstract class JacksonJSONContext<M extends Message.Mutable, I extends M> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JavaType rootArrayType;

    protected JacksonJSONContext() {
        rootArrayType = objectMapper.constructType(rootArrayClass());
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    protected abstract Class<I[]> rootArrayClass();

    public M[] parse(Reader reader) throws ParseException {
        try {
            return getObjectMapper().readValue(reader, rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public M[] parse(String json) throws ParseException {
        try {
            return getObjectMapper().readValue(json, rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    public JSONContext.AsyncParser newAsyncParser() {
        try {
            JsonParser jsonParser = objectMapper.getFactory().createNonBlockingByteArrayParser();
            return new AsyncJsonParser(jsonParser);
        } catch (Throwable x) {
            return null;
        }
    }

    public String generate(M message) {
        try {
            return getObjectMapper().writeValueAsString(message);
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
    }

    public String generate(List<M> messages) {
        try {
            Message.Mutable[] mutables = new Message.Mutable[messages.size()];
            messages.toArray(mutables);
            return getObjectMapper().writeValueAsString(mutables);
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
    }

    public JSONContext.Parser getParser() {
        return new ObjectMapperParser();
    }

    public JSONContext.Generator getGenerator() {
        return new ObjectMapperGenerator();
    }

    private class ObjectMapperParser implements JSONContext.Parser {
        @Override
        public <R> R parse(Reader reader, Class<R> type) throws ParseException {
            try {
                return getObjectMapper().readValue(reader, type);
            } catch (IOException x) {
                throw (ParseException)new ParseException("", -1).initCause(x);
            }
        }
    }

    protected class ObjectMapperGenerator implements JSONContext.Generator {
        @Override
        public String generate(Object object) {
            try {
                return getObjectMapper().writeValueAsString(object);
            } catch (IOException x) {
                throw new RuntimeException(x);
            }
        }
    }

    private class AsyncJsonParser implements JSONContext.AsyncParser {
        private final JsonParser jsonParser;
        private final TokenBuffer tokenBuffer;

        public AsyncJsonParser(JsonParser jsonParser) {
            this.jsonParser = jsonParser;
            this.tokenBuffer = new TokenBuffer(jsonParser);
        }

        @Override
        public void parse(byte[] bytes, int offset, int length) {
            try {
                NonBlockingInputFeeder feeder = jsonParser.getNonBlockingInputFeeder();
                if (feeder instanceof ByteArrayFeeder) {
                    ((ByteArrayFeeder)feeder).feedInput(bytes, offset, offset + length);
                    parseInput();
                } else if (feeder instanceof ByteBufferFeeder) {
                    parse(ByteBuffer.wrap(bytes, offset, length));
                } else {
                    throw new UnsupportedOperationException();
                }
            } catch (IOException x) {
                throw new IllegalStateException(x);
            }
        }

        @Override
        public void parse(ByteBuffer buffer) {
            try {
                NonBlockingInputFeeder feeder = jsonParser.getNonBlockingInputFeeder();
                if (feeder instanceof ByteBufferFeeder) {
                    ((ByteBufferFeeder)feeder).feedInput(buffer);
                    parseInput();
                } else if (feeder instanceof ByteArrayFeeder) {
                    if (buffer.hasArray()) {
                        parse(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                    } else {
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        parse(bytes, 0, bytes.length);
                    }
                } else {
                    throw new UnsupportedOperationException();
                }
            } catch (IOException x) {
                throw new IllegalStateException(x);
            }
        }

        private void parseInput() throws IOException {
            while (true) {
                JsonToken jsonToken = jsonParser.nextToken();
                if (jsonToken == JsonToken.NOT_AVAILABLE) {
                    break;
                }
                tokenBuffer.copyCurrentEvent(jsonParser);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> R complete() {
            try {
                NonBlockingInputFeeder feeder = jsonParser.getNonBlockingInputFeeder();
                feeder.endOfInput();
                jsonParser.nextToken();
                M[] result = objectMapper.readValue(tokenBuffer.asParser(), objectMapper.constructType(rootArrayClass()));
                return (R)Arrays.asList(result);
            } catch (IOException x) {
                throw new IllegalArgumentException(x);
            }
        }
    }
}
