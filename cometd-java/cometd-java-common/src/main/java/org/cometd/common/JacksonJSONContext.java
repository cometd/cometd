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
import java.io.InputStream;
import java.io.Reader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.common.JSONContext.NonBlockingParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JacksonJSONContext<T extends Message.Mutable, I extends T> {
    private static final Logger _logger = LoggerFactory.getLogger(JacksonJSONContext.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JavaType rootArrayType;

    protected JacksonJSONContext() {
        rootArrayType = objectMapper.constructType(rootArrayClass());
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    protected abstract Class<I[]> rootArrayClass();
    
    public NonBlockingParser<T> newNonBlockingParser() {
        try {
            JsonParser nonBlockingParser = objectMapper.getFactory().createNonBlockingByteArrayParser();
            return new NonBlockingParser<T>() {
                ByteArrayFeeder feeder = (ByteArrayFeeder) nonBlockingParser.getNonBlockingInputFeeder();
                JsonToken token = JsonToken.NOT_AVAILABLE;
                int objectDepth = 0;
                int arrayDepth = 0;
                TokenBuffer tokenBuffer;
                
                @Override
                public List<T> feed(byte[] block) throws ParseException {
                    try {
                        feeder.feedInput(block, 0, block.length);
                        return takeWhatYouCan();
                    } catch (IOException x) {
                        throw (ParseException)new ParseException("", -1).initCause(x);
                    }
                }

                @Override
                public List<T> done() throws ParseException {
                    try {
                        feeder.endOfInput();
                        return takeWhatYouCan();
                    } catch (IOException x) {
                        throw (ParseException)new ParseException("", -1).initCause(x);
                    }
                }
                
                private List<T> takeWhatYouCan() throws IOException, ParseException {
                    _logger.debug("Starting block");
                    // see what we can read
                    List<T> foundObjects = new ArrayList<>();
                    token = nonBlockingParser.nextToken();
                    _logger.debug("STARTING: " + token);
                    while (token != JsonToken.NOT_AVAILABLE && token != null) {
                        if (token == JsonToken.START_OBJECT) {
                            if (objectDepth == 0) {
                                tokenBuffer = new TokenBuffer(nonBlockingParser); // prepare for the next object
                                tokenBuffer.writeStartArray();
                            }
                            objectDepth++;
                        } else if (token == JsonToken.START_ARRAY) {
                            arrayDepth++;
                        }
                        if (token == JsonToken.END_OBJECT) {
                            objectDepth--;
                        } else if (token == JsonToken.END_ARRAY) {
                            arrayDepth--;
                        }

                        if (tokenBuffer != null) {
                            tokenBuffer.copyCurrentEvent(nonBlockingParser);
                        }

                        // we finished an object 
                        if (token == JsonToken.END_OBJECT && objectDepth == 0 && arrayDepth == 1) {
                            tokenBuffer.writeEndArray();
                            T[] t = (T[])parse(tokenBuffer);
                            _logger.debug("" + (HashMapMessage)t[0]);
                            foundObjects.addAll(Arrays.asList(t));
                        }
                        
                        token = nonBlockingParser.nextToken();
                        _logger.debug(token + " o:" + objectDepth + ",a:" + arrayDepth);
                    }
                    return foundObjects;
                }
                
            };
        } catch (IOException e) {
            // it is a bug in the code if we call newNonBlockingParser when we can't actually make one
            throw new RuntimeException(e);
        }
    }

    public T[] parse(InputStream stream) throws ParseException {
        try {
            return getObjectMapper().readValue(stream, rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public T[] parse(Reader reader) throws ParseException {
        try {
            return getObjectMapper().readValue(reader, rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public T[] parse(String json) throws ParseException {
        try {
            return getObjectMapper().readValue(json, rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    public T[] parse(TokenBuffer buffer) throws ParseException {
        try {
            return getObjectMapper().readValue(buffer.asParser(), rootArrayType);
        } catch (IOException x) {
            throw (ParseException)new ParseException(null, -1).initCause(x);
        }
    }

    public String generate(T message) {
        try {
            return getObjectMapper().writeValueAsString(message);
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
    }

    public String generate(List<T> messages) {
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
        public <T> T parse(Reader reader, Class<T> type) throws ParseException {
            try {
                return getObjectMapper().readValue(reader, type);
            } catch (IOException x) {
                throw (ParseException)new ParseException("", -1).initCause(x);
            }
        }
    }

    private class ObjectMapperGenerator implements JSONContext.Generator {
        @Override
        public String generate(Object object) {
            try {
                return getObjectMapper().writeValueAsString(object);
            } catch (IOException x) {
                throw new RuntimeException(x);
            }
        }
    }
}
