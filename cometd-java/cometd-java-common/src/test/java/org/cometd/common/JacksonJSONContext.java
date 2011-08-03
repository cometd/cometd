/*
 * Copyright (c) 2011 the original author or authors.
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
import java.text.ParseException;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.module.SimpleModule;
import org.cometd.bayeux.Message;

public class JacksonJSONContext implements JSONContext<Message.Mutable>
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JacksonJSONContext()
    {
        objectMapper.registerModule(new CometDModule("cometd", Version.unknownVersion()));
    }

    public Message.Mutable[] parse(Reader reader) throws ParseException
    {
        try
        {
            return objectMapper.readValue(reader, HashMapMessage[].class);
        }
        catch (IOException x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public Message.Mutable[] parse(String json) throws ParseException
    {
        try
        {
            return objectMapper.readValue(json, HashMapMessage[].class);
        }
        catch (IOException x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public String generate(Message.Mutable message)
    {
        try
        {
            return objectMapper.writeValueAsString(message);
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
    }

    public String generate(Message.Mutable... messages)
    {
        try
        {
            return objectMapper.writeValueAsString(messages);
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
    }

    private class CometDModule extends SimpleModule
    {
        private CometDModule(String name, Version version)
        {
            super(name, version);
            addSerializer(JSONLiteral.class, new JSONLiteralSerializer());
        }
    }

    private class JSONLiteralSerializer extends JsonSerializer<JSONLiteral>
    {
        @Override
        public void serialize(JSONLiteral value, JsonGenerator jgen, SerializerProvider provider) throws IOException
        {
            jgen.writeRaw(value.toString());
        }
    }
}
