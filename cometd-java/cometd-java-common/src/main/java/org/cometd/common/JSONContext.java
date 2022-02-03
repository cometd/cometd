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

import java.io.Reader;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.List;

import org.cometd.bayeux.Message;

/**
 * <p>Abstraction for JSON parsing and generation.</p>
 *
 * @param <T> the type of message
 */
public interface JSONContext<T extends Message.Mutable> {
    /**
     * <p>Parses an array of messages from the given reader.</p>
     *
     * @param reader the reader to parse from
     * @return an array of messages
     * @throws ParseException in case of parsing errors
     */
    public T[] parse(Reader reader) throws ParseException;

    /**
     * <p>Parses an array of messages from the given string.</p>
     *
     * @param json the JSON string to parse from
     * @return an array of messages
     * @throws ParseException in case of parsing errors
     */
    public T[] parse(String json) throws ParseException;

    /**
     * @return a new {@link AsyncParser} instance, or null if non-blocking parsing is not supported
     */
    public default AsyncParser newAsyncParser() {
        return null;
    }

    /**
     * <p>Converts a single message to a JSON string.</p>
     *
     * @param message the message to stringify
     * @return the JSON string for the message
     */
    public String generate(T message);

    /**
     * <p>Converts a list of messages to a JSON string.</p>
     *
     * @param messages the list of messages to stringify
     * @return the JSON string for the messages
     */
    public String generate(List<T> messages);

    /**
     * @return a synchronous JSON parser to parse any JSON string
     */
    public Parser getParser();

    /**
     * @return a JSON generator to stringify any object to a JSON string
     */
    public Generator getGenerator();

    /**
     * <p>Client specific {@link JSONContext} that binds to {@link Message.Mutable}.</p>
     */
    public interface Client extends JSONContext<Message.Mutable> {
    }

    /**
     * A blocking JSON parser.
     */
    public interface Parser {
        /**
         * <p>Parses a JSON string from the given reader.</p>
         *
         * @param reader the reader to parse from
         * @param type the type to cast the result to
         * @param <R> the type of the result
         * @return the result of the JSON parsing
         * @throws ParseException if the JSON is malformed
         */
        public <R> R parse(Reader reader, Class<R> type) throws ParseException;
    }

    /**
     * A non-blocking JSON parser.
     */
    public interface AsyncParser {
        /**
         * @param bytes the bytes chunk to parse
         * @param offset the offset to start parsing from
         * @param length the number of bytes to parse
         */
        public default void parse(byte[] bytes, int offset, int length) {
            parse(ByteBuffer.wrap(bytes, offset, length));
        }

        /**
         * @param buffer the buffer chunk to parse
         */
        public void parse(ByteBuffer buffer);

        /**
         * <p>Signals the end of the JSON string content to this parser and returns the parsed object.</p>
         *
         * @param <R> the type to cast the result to
         * @return the result of the JSON parsing
         */
        public <R> R complete();
    }

    /**
     * A JSON stringifier.
     */
    public interface Generator {
        /**
         * @param object the object to stringify
         * @return the JSON string representing the object
         */
        public String generate(Object object);
    }
}
