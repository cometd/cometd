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
import org.cometd.bayeux.server.ServerMessage;

public interface JSONContext {
    public interface Client extends JSONParserGenerator<Message.Mutable> {
    }

    public interface Server extends JSONParserGenerator<ServerMessage.Mutable> {
    }

    public interface Parser {
        public <T> T parse(Reader reader, Class<T> type) throws ParseException;
    }

    public interface Generator {
        public String generate(Object object);
    }
}

interface JSONParserGenerator<T extends Message.Mutable> {
    public T[] parse(InputStream stream) throws ParseException;

    public T[] parse(Reader reader) throws ParseException;

    public T[] parse(String json) throws ParseException;

    public String generate(T message);

    public String generate(List<T> messages);

    public JSONContext.Parser getParser();

    public JSONContext.Generator getGenerator();
}
