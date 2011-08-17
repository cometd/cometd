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

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.JavaType;
import org.cometd.bayeux.Message;

public abstract class JacksonJSONContext<T extends Message.Mutable, I extends T>
{
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JavaType rootArrayType;

    protected JacksonJSONContext()
    {
        rootArrayType = objectMapper.constructType(rootArrayClass());
    }

    protected ObjectMapper getObjectMapper()
    {
        return objectMapper;
    }

    protected abstract Class<I[]> rootArrayClass();

    public T[] parse(Reader reader) throws ParseException
    {
        try
        {
            return getObjectMapper().readValue(reader, rootArrayType);
        }
        catch (IOException x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public T[] parse(String json) throws ParseException
    {
        try
        {
            return getObjectMapper().readValue(json, rootArrayType);
        }
        catch (IOException x)
        {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    public String generate(T message)
    {
        try
        {
            return getObjectMapper().writeValueAsString(message);
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
    }

    public String generate(T[] messages)
    {
        try
        {
            return getObjectMapper().writeValueAsString(messages);
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
    }
}
