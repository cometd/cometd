package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessageImpl extends HashMapMessage implements ServerMessage.Mutable, JSON.Generator
{
    private static final long serialVersionUID = 6412048662640296067L;

    private volatile transient ServerMessage.Mutable _associated;
    private volatile boolean _lazy = false;
    private volatile String _json;

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

    public void freeze()
    {
        assert _json == null;
        _json = getJSON();
    }

    @Override
    public String getJSON()
    {
        if (_json == null)
            return super.getJSON();
        return _json;
    }

    @Override
    public Object getData()
    {
        Object data = super.getData();
        if (_json != null && data instanceof Map)
            return Collections.unmodifiableMap((Map<String, Object>)data);
        return data;
    }

    @Override
    public Object put(String key, Object value)
    {
        if (_json != null)
            throw new UnsupportedOperationException();
        return super.put(key, value);
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet()
    {
        if (_json != null)
            return new ImmutableEntrySet(super.entrySet());
        return super.entrySet();
    }

    @Override
    public Map<String, Object> getDataAsMap()
    {
        Map<String, Object> data = super.getDataAsMap();
        if (_json != null && data != null)
            return Collections.unmodifiableMap(data);
        return data;
    }

    @Override
    public Map<String, Object> getExt()
    {
        Map<String, Object> ext = super.getExt();
        if (_json != null && ext != null)
            return Collections.unmodifiableMap(ext);
        return ext;
    }

    @Override
    public Map<String, Object> getAdvice()
    {
        Map<String, Object> advice = super.getAdvice();
        if (_json != null && advice != null)
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

    private static class ImmutableEntrySet extends AbstractSet<Map.Entry<String, Object>>
    {
        private final Set<Map.Entry<String, Object>> delegate;

        private ImmutableEntrySet(Set<Map.Entry<String, Object>> delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator()
        {
            return new ImmutableEntryIterator(delegate.iterator());
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        private static class ImmutableEntryIterator implements Iterator<Map.Entry<String, Object>>
        {
            private final Iterator<Map.Entry<String, Object>> delegate;

            private ImmutableEntryIterator(Iterator<Map.Entry<String, Object>> delegate)
            {
                this.delegate = delegate;
            }

            public boolean hasNext()
            {
                return delegate.hasNext();
            }

            public Map.Entry<String, Object> next()
            {
                return new ImmutableEntry(delegate.next());
            }

            public void remove()
            {
                throw new UnsupportedOperationException();
            }

            private static class ImmutableEntry implements Map.Entry<String, Object>
            {
                private final Map.Entry<String, Object> delegate;

                private ImmutableEntry(Map.Entry<String, Object> delegate)
                {
                    this.delegate = delegate;
                }

                public String getKey()
                {
                    return delegate.getKey();
                }

                public Object getValue()
                {
                    return delegate.getValue();
                }

                public Object setValue(Object value)
                {
                    throw new UnsupportedOperationException();
                }
            }
        }
    }
}
