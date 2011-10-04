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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public abstract class JettyJSONContext<T extends Message.Mutable>
{
    private JSON _jsonParser = new FieldJSON();
    private JSON _messageParser = new MessageJSON();
    private JSON _messagesParser = new MessagesJSON();

    protected JettyJSONContext()
    {
    }

    protected JSON getJSON()
    {
        return _jsonParser;
    }

    protected abstract T newRoot();

    protected abstract T[] newRootArray(int size);

    public T[] parse(InputStream stream) throws ParseException
    {
        return parse(new InputStreamReader(stream, Charset.forName("UTF-8")));
    }

    public T[] parse(Reader reader) throws ParseException
    {
        try
        {
            Object object = _messagesParser.parse(new JSON.ReaderSource(reader));
            return adapt(object);
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public T[] parse(String json) throws ParseException
    {
        try
        {
            Object object = _messagesParser.parse(new JSON.StringSource(json));
            return adapt(object);
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    @SuppressWarnings("unchecked")
    private T[] adapt(Object object)
    {
        if (object == null)
            return null;
        if (object.getClass().isArray())
            return (T[])object;
        T[] result = newRootArray(1);
        result[0] = (T)object;
        return result;
    }

    public String generate(T message)
    {
        return _messageParser.toJSON(message);
    }

    public String generate(T[] messages)
    {
        return _messagesParser.toJSON(messages);
    }

    private class FieldJSON extends JSON
    {
        // Allows for optimizations
    }

    private class MessageJSON extends FieldJSON
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return newRoot();
        }

        @Override
        protected JSON contextFor(String field)
        {
            return getJSON();
        }
    }

    private class MessagesJSON extends FieldJSON
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return newRoot();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return newRootArray(size);
        }

        @Override
        protected JSON contextFor(String field)
        {
            return getJSON();
        }

        @Override
        protected JSON contextForArray()
        {
            return _messageParser;
        }
    }
}
