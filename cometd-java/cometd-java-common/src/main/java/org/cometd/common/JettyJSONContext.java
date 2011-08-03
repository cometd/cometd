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
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public abstract class JettyJSONContext<T extends Message.Mutable>
{
    private JSON _jsonParser = new FieldJSON();
    private JSON _messageParser = new MessageJSON();
    private JSON _messagesParser = new MessagesJSON();

    public JettyJSONContext()
    {
        _jsonParser.addConvertor(JSONLiteral.class, new JSONLiteralConvertor());
    }

    protected abstract T newRoot();

    protected abstract T[] newRootArray(int size);

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

    private T[] adapt(Object object)
    {
        if (object.getClass().isArray())
            return (T[])object;
        T[] result = newRootArray(1);
        result[0] = (T)object;
        return result;
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
        @Override
        public void appendMap(Appendable buffer, Map<?, ?> map)
        {
            try
            {
                if (map == null)
                {
                    appendNull(buffer);
                    return;
                }

                buffer.append('{');
                Iterator<?> iter = map.entrySet().iterator();
                while (iter.hasNext())
                {
                    Map.Entry<?,?> entry = (Map.Entry<?,?>)iter.next();
                    appendString(buffer, entry.getKey().toString());
                    buffer.append(':');
                    append(buffer, entry.getValue());
                    if (iter.hasNext())
                        buffer.append(',');
                }

                buffer.append('}');
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void appendString(Appendable buffer, String string)
        {
            if (string == null)
            {
                appendNull(buffer);
            }
            else
            {
//                QuotedStringTokenizer.quote(buffer, string);
                StringQuoter.quote(buffer, string);
            }
        }
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
            return _jsonParser;
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
            return _jsonParser;
        }

        @Override
        protected JSON contextForArray()
        {
            return _messageParser;
        }
    }

    private class JSONLiteralConvertor implements JSON.Convertor
    {
        public void toJSON(Object obj, JSON.Output out)
        {
            JSONLiteral literal = (JSONLiteral)obj;
            out.add(new JSON.Literal(literal.toString()));
        }

        public Object fromJSON(Map object)
        {
            return object;
        }
    }

    private static class StringQuoter
    {
        private static final char[] escapes = new char[31];
        static
        {
            Arrays.fill(escapes, (char)-1);
            escapes['\b'] = 'b';
            escapes['\t'] = 't';
            escapes['\n'] = 'n';
            escapes['\f'] = 'f';
            escapes['\r'] = 'r';
        }

        public static void quote(Appendable buffer, String input)
        {
            try
            {
                buffer.append('"');
                for (int i = 0; i < input.length(); ++i)
                {
                    char c = input.charAt(i);
                    if (c >= 32)
                    {
                        if (c == '"' || c == '\\')
                            buffer.append('\\');
                        buffer.append(c);
                    }
                    else
                    {
                        char escape = escapes[c];
                        if (escape == -1)
                        {
                            // Unicode escape
                            buffer.append('\\').append('0').append('0');
                            if (c < 0x10)
                                buffer.append('0');
                            buffer.append(Integer.toString(c, 16));
                        }
                        else
                        {
                            buffer.append('\\').append(escape);
                        }
                    }
                }
                buffer.append('"');
            }
            catch (IOException x)
            {
                throw new RuntimeException(x);
            }
        }
    }
}
