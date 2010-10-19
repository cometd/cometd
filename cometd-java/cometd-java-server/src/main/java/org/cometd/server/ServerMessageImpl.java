package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.util.Collections;
import java.util.Map;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessageImpl extends HashMapMessage implements ServerMessage.Mutable, JSON.Generator
{
    private volatile ServerMessage.Mutable _associated;
    private volatile boolean _lazy = false;
    private volatile String json;

    public ServerMessage.Mutable getAssociated()
    {
        return _associated;
    }

    public void setAssociated(ServerMessage.Mutable associated)
    {
        _associated = associated;
    }

    public boolean isLazy()
    {
        return _lazy;
    }

    public void setLazy(boolean lazy)
    {
        _lazy = lazy;
    }

    @Override
    public void clear()
    {
        super.clear();
        _associated = null;
        _lazy = false;
    }

    public void freeze()
    {
        json = getJSON();
    }

    @Override
    public String getJSON()
    {
        if (json == null)
            return super.getJSON();
        return json;
    }

    @Override
    public Object getData()
    {
        Object data = super.getData();
        if (json != null && data instanceof Map)
            return Collections.unmodifiableMap((Map<String, Object>)data);
        return data;
    }

    @Override
    public Object put(String key, Object value)
    {
        if (json != null)
            throw new UnsupportedOperationException();
        return super.put(key, value);
    }

    @Override
    public Map<String, Object> getDataAsMap()
    {
        Map<String, Object> data = super.getDataAsMap();
        if (json != null && data != null)
            return Collections.unmodifiableMap(data);
        return data;
    }

    @Override
    public Map<String, Object> getExt()
    {
        Map<String, Object> ext = super.getExt();
        if (json != null && ext != null)
            return Collections.unmodifiableMap(ext);
        return ext;
    }

    @Override
    public Map<String, Object> getAdvice()
    {
        Map<String, Object> advice = super.getAdvice();
        if (json != null && advice != null)
            return Collections.unmodifiableMap(advice);
        return advice;
    }

    private static JSON serverMessageParser = new JSON()
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return new ServerMessageImpl();
        }

        @Override
        protected JSON contextFor(String field)
        {
            return jsonParser;
        }
    };
    private static JSON serverMessagesParser = new JSON()
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
            return jsonParser;
        }

        @Override
        protected JSON contextForArray()
        {
            return serverMessageParser;
        }
    };

    public static ServerMessage.Mutable[] parseServerMessages(Reader reader, boolean jsonDebug) throws ParseException, IOException
    {
        if (jsonDebug)
            return parseServerMessages(IO.toString(reader));

        try
        {
            Object batch = serverMessagesParser.parse(new JSON.ReaderSource(reader));
            if (batch == null)
                return new ServerMessage.Mutable[0];
            if (batch.getClass().isArray())
                return (ServerMessage.Mutable[])batch;
            return new ServerMessage.Mutable[]{(ServerMessage.Mutable)batch};
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException("", -1).initCause(x);
        }
    }

    public static ServerMessage.Mutable[] parseServerMessages(String s) throws ParseException
    {
        try
        {
            Object batch = serverMessagesParser.parse(new JSON.StringSource(s));
            if (batch == null)
                return new ServerMessage.Mutable[0];
            if (batch.getClass().isArray())
                return (ServerMessage.Mutable[])batch;
            return new ServerMessage.Mutable[]{(ServerMessage.Mutable)batch};
        }
        catch (Exception x)
        {
            throw (ParseException)new ParseException(s, -1).initCause(x);
        }
    }
}
