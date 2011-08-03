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

package org.cometd.server;

import java.io.Reader;
import java.text.ParseException;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JSONContext;
import org.eclipse.jetty.util.ajax.JSON;

public class JettyServerJSONContext implements JSONContext<ServerMessage.Mutable>
{
    public ServerMessage.Mutable[] parse(Reader reader) throws ParseException
    {
        try
        {
            Object object = _serverMessagesParser.parse(new JSON.ReaderSource(reader));
            if (object instanceof Message.Mutable)
                return new ServerMessage.Mutable[]{(ServerMessage.Mutable)object};
            return (ServerMessage.Mutable[])object;
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public ServerMessage.Mutable[] parse(String json) throws ParseException
    {
        try
        {
            Object object = _serverMessagesParser.parse(new JSON.StringSource(json));
            if (object instanceof Message.Mutable)
                return new ServerMessage.Mutable[]{(ServerMessage.Mutable)object};
            return (ServerMessage.Mutable[])object;
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    public String generate(ServerMessage.Mutable message)
    {
        return _serverMessageParser.toJSON(message);
    }

    public String generate(ServerMessage.Mutable... messages)
    {
        return _serverMessagesParser.toJSON(messages);
    }

    private JSON _jsonParser = new FieldJSON();
    private JSON _serverMessageParser = new ServerMessageJSON();
    private JSON _serverMessagesParser = new ServerMessagesJSON();

    private class FieldJSON extends JSON
    {
        // TODO: same optimizations done in JettyJSONContext
    }

    private class ServerMessageJSON extends FieldJSON
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return new ServerMessageImpl();
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _jsonParser;
        }
    }

    private class ServerMessagesJSON extends FieldJSON
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return new ServerMessageImpl();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new ServerMessage.Mutable[size];
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _jsonParser;
        }

        @Override
        protected JSON contextForArray()
        {
            return _serverMessageParser;
        }
    }
}
