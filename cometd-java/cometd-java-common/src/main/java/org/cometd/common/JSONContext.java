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

import java.io.InputStream;
import java.io.Reader;
import java.text.ParseException;
import java.util.List;

import org.cometd.bayeux.Message;

public interface JSONContext<T extends Message.Mutable> {
    public T[] parse(InputStream stream) throws ParseException;

    public T[] parse(Reader reader) throws ParseException;

    public T[] parse(String json) throws ParseException;

    public String generate(T message);

    public String generate(List<T> messages);

    public Parser getParser();
    
    public NonBlockingParser<T> newNonBlockingParser();
    
    public boolean supportsNonBlockingParser();
    
    public Generator getGenerator();

    public interface Client extends JSONContext<Message.Mutable> {
    }

    public interface Parser {
        public <T> T parse(Reader reader, Class<T> type) throws ParseException;
    }
    
    public interface NonBlockingParser<T> {
        public List<T> feed(byte[] block) throws ParseException;

        public List<T> done() throws ParseException;
    }

    public interface Generator {
        public String generate(Object object);
    }
}
