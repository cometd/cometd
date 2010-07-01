package org.cometd.server;

import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.ChannelId;
import org.cometd.util.ImmutableHashMap;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringMap;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessageImpl extends AbstractMap<String,Object> implements ServerMessage, JSON.Generator
{
    private final ImmutableHashMap<String,Object> _immutable = new ImmutableHashMap<String,Object>(16)
    {
    	@Override
    	protected void onChange(String key) throws UnsupportedOperationException
    	{
    		_jsonString=null;
    	}
    } ;

    private final MutableMessage _mutable;

    private volatile ServerMessage _associated;
    private volatile boolean _lazy=false;
    private volatile String _jsonString;

    /* ------------------------------------------------------------ */
    public ServerMessageImpl()
    {
        _mutable = new MutableMessage();
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable asMutable()
    {
        return _mutable;
    }

    /* ------------------------------------------------------------ */
    public void addJSON(Appendable buffer)
    {
        try
        {
            buffer.append(getJSON());
        }
        catch(IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsKey(Object key)
    {
        return _immutable.containsKey(key);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsValue(Object value)
    {
        return _immutable.containsValue(value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<java.util.Map.Entry<String, Object>> entrySet()
    {
        return _immutable.entrySet();
    }

    /* ------------------------------------------------------------ */
    @Override
    public Object get(Object key)
    {
        return _immutable.get(key);
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getAdvice()
    {
        Object advice=_mutable._advice.asImmutable().getValue();
        if (advice instanceof JSON.Literal)
            return (Map<String, Object>)JSON.parse(advice.toString());
        return (Map<String, Object>)advice;
    }

    /* ------------------------------------------------------------ */
    public ServerMessage getAssociated()
    {
        return _associated;
    }

    /* ------------------------------------------------------------ */
    public String getChannel()
    {
        return (String)_mutable._channel.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getClientId()
    {
        return (String)_mutable._clientId.getValue();
    }

    /* ------------------------------------------------------------ */
    public Object getData()
    {
        return _mutable._data.asImmutable().getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getDataAsMap()
    {
        return (Map<String,Object>)_mutable._data.asImmutable().getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getExt()
    {
        return (Map<String, Object>)_mutable._ext.asImmutable().getValue();
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        Object id = _mutable._id.getValue();
        return id == null ? null : id.toString();
    }

    /* ------------------------------------------------------------ */
    public String getJSON()
    {
        if (_jsonString == null)
        {
            StringBuilder buf=new StringBuilder(__msgJSON.getStringBufferSize());
            __msgJSON.appendMap(buf,this);
            _jsonString=buf.toString();
        }
        return _jsonString;
    }

    /* ------------------------------------------------------------ */
    /**
     * Lazy messages are queued but do not wake up waiting clients.
     *
     * @return true if message is lazy
     */
    public boolean isLazy()
    {
        return _lazy;
    }

    /* ------------------------------------------------------------ */
    public boolean isMeta()
    {
        return ChannelId.isMeta((String)_mutable._channel.getValue());
    }

    /* ------------------------------------------------------------ */
    public boolean isSuccessful()
    {
        Boolean bool=(Boolean)get(Message.SUCCESSFUL_FIELD);
        return bool != null && bool.booleanValue();
    }

    /* ------------------------------------------------------------ */
    public void setAssociated(ServerMessage associated)
    {
        _associated=associated;
    }

    /* ------------------------------------------------------------ */
    public void setData(Object data)
    {
        _mutable._data.asImmutable().setValue(data);
    }

    /* ------------------------------------------------------------ */
    /**
     * Lazy messages are queued but do not wake up waiting clients.
     *
     * @param lazy
     *            true if message is lazy
     */
    public void setLazy(boolean lazy)
    {
        _lazy=lazy;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int size()
    {
        return _immutable.size();
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return "|"+getJSON()+"|";
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class MutableMessage extends AbstractMap<String,Object> implements ServerMessage.Mutable
    {
        private final ImmutableHashMap<String,Object>.Mutable _mutable=_immutable.asMutable();
        private final ImmutableHashMap.MutableEntry<String,Object> _advice;
        private final ImmutableHashMap.MutableEntry<String,Object> _channel;
        private final ImmutableHashMap.MutableEntry<String,Object> _clientId;
        private final ImmutableHashMap.MutableEntry<String,Object> _data;
        private final ImmutableHashMap.MutableEntry<String,Object> _ext;
        private final ImmutableHashMap.MutableEntry<String,Object> _id;

        MutableMessage()
        {
            _advice=_mutable.getEntryReference(Message.ADVICE_FIELD);
            _channel=_mutable.getEntryReference(Message.CHANNEL_FIELD);
            _clientId=_mutable.getEntryReference(Message.CLIENT_ID_FIELD);
            _data=_mutable.getEntryReference(Message.DATA_FIELD);
            _ext=_mutable.getEntryReference(Message.EXT_FIELD);
            _id=_mutable.getEntryReference(Message.ID_FIELD);
        }

        public ServerMessage.Mutable asMutable()
        {
            return this;
        }

        public ServerMessageImpl asImmutable()
        {
            return ServerMessageImpl.this;
        }

        @Override
        public void clear()
        {
            setAssociated(null);
            _jsonString=null;
            _lazy=false;
            super.clear();
        }

        @Override
        public boolean containsKey(Object key)
        {
            return _mutable.containsKey(key);
        }

        @Override
        public Set<Map.Entry<String,Object>> entrySet()
        {
            return _mutable.entrySet();
        }

        @Override
        public Object get(Object key)
        {
            return _mutable.get(key);
        }

        public Map<String, Object> getAdvice()
        {
            Object advice=_advice.getValue();
            if (advice instanceof JSON.Literal)
            {
                advice =JSON.parse(advice.toString());
                _advice.setValue(advice);
            }
            return (Map<String, Object>)advice;
        }

        public Map<String, Object> getDataAsMap()
        {
            Map<String, Object> data=(Map<String, Object>)_data.getValue();
            return data;
        }

        public Map<String, Object> getDataAsMap(boolean create)
        {
            Map<String, Object> data=(Map<String, Object>)_data.getValue();
            if (create && data==null)
            {
                data=new ImmutableHashMap<String,Object>(16).asMutable();;
                _data.setValue(data);
            }
            return data;
        }

        public Map<String, Object> getAdvice(boolean create)
        {
            Object advice=_advice.getValue();
            if (advice instanceof JSON.Literal)
            {
                advice =JSON.parse(advice.toString());
                _advice.setValue(advice);
            }
            if (create && advice==null)
            {
                advice=new ImmutableHashMap<String,Object>(8).asMutable();
                _advice.setValue(advice);
            }
            return (Map<String, Object>)advice;
        }

        public String getChannel()
        {
            return (String)_channel.getValue();
        }

        public String getClientId()
        {
            return (String)_clientId.getValue();
        }

        public Object getData()
        {
            return _data.getValue();
        }

        public Entry<String,Object> getEntry(String key)
        {
            return _mutable.getEntry(key);
        }

        public Map<String, Object> getExt()
        {
            return (Map<String, Object>)_ext.getValue();
        }

        public Map<String,Object> getExt(boolean create)
        {
            Object ext=_ext.getValue();
            if (ext==null && !create)
                return null;

            if (ext instanceof Map)
                return (Map<String,Object>)ext;

            if (ext instanceof JSON.Literal)
            {
                ext=__json.fromJSON(ext.toString());
                _ext.setValue(ext);
                return (Map<String,Object>)ext;
            }

            ext=new ImmutableHashMap<String,Object>(16).asMutable();;
            _ext.setValue(ext);
            return (Map<String,Object>)ext;
        }

        public String getId()
        {
            Object id = _id.getValue();
            return id == null ? null : id.toString();
        }

        public boolean isLazy()
        {
            return _lazy;
        }

        public boolean isMeta()
        {
            return ChannelId.isMeta((String)_channel.getValue());
        }

        @Override
        public Object put(String key, Object value)
        {
            return _mutable.put(key,value);
        }

        @Override
        public Object remove(Object key)
        {
            return _mutable.remove(key);
        }

        public void setData(Object data)
        {
            _data.setValue(data);
        }

        public void setLazy(boolean lazy)
        {
            _lazy=lazy;
        }

        @Override
        public int size()
        {
            return _mutable.size();
        }

        public ServerMessage getAssociated()
        {
            return ServerMessageImpl.this.getAssociated();
        }

        public void setAssociated(ServerMessage message)
        {
            ServerMessageImpl.this.setAssociated(message);
        }

        public void setClientId(String clientId)
        {
            _clientId.setValue(clientId);
        }

        public void setId(String id)
        {
            _id.setValue(id);
        }

        public void setChannel(String channel)
        {
            _channel.setValue(channel);
        }

        public String getJSON()
        {
            return ServerMessageImpl.this.getJSON();
        }

        public boolean isSuccessful()
        {
            return ServerMessageImpl.this.isSuccessful();
        }

        public void setSuccessful(boolean successful)
        {
            put(SUCCESSFUL_FIELD, successful ?Boolean.TRUE:Boolean.FALSE);
        }

        public String toString()
        {
            return getJSON();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static StringMap __fieldStrings=new StringMap();
    private static StringMap __valueStrings=new StringMap();
    {
        __fieldStrings.put(Message.ADVICE_FIELD,Message.ADVICE_FIELD);
        __fieldStrings.put(Message.CHANNEL_FIELD,Message.CHANNEL_FIELD);
        __fieldStrings.put(Message.CLIENT_ID_FIELD,Message.CLIENT_ID_FIELD);
        __fieldStrings.put(Message.DATA_FIELD,Message.DATA_FIELD);
        __fieldStrings.put(Message.ERROR_FIELD,Message.ERROR_FIELD);
        __fieldStrings.put(Message.EXT_FIELD,Message.EXT_FIELD);
        __fieldStrings.put(Message.ID_FIELD,Message.ID_FIELD);
        __fieldStrings.put(Message.SUBSCRIPTION_FIELD,Message.SUBSCRIPTION_FIELD);
        __fieldStrings.put(Message.SUCCESSFUL_FIELD,Message.SUCCESSFUL_FIELD);
        __fieldStrings.put(Message.TIMESTAMP_FIELD,Message.TIMESTAMP_FIELD);
        __fieldStrings.put(Message.TRANSPORT_FIELD,Message.TRANSPORT_FIELD);
        __fieldStrings.put("connectionType","connectionType");

        __valueStrings.put(Channel.META_CONNECT,Channel.META_CONNECT);
        __valueStrings.put(Channel.META_DISCONNECT,Channel.META_DISCONNECT);
        __valueStrings.put(Channel.META_HANDSHAKE,Channel.META_HANDSHAKE);
        __valueStrings.put(Channel.META_SUBSCRIBE,Channel.META_SUBSCRIBE);
        __valueStrings.put(Channel.META_UNSUBSCRIBE,Channel.META_UNSUBSCRIBE);
        __valueStrings.put("long-polling","long-polling");
    }


    /* ------------------------------------------------------------ */
    /** Add a JSON convertor.
     * Add a JSON convertor to the JSON instance used to convert
     * message fields.
     * @see JSON#addConvertor(Class, org.eclipse.jetty.util.ajax.JSON.Convertor)
     */
    public static void addConvertor(Class forClass,JSON.Convertor convertor)
    {
        __json.addConvertor(forClass,convertor);
    }

    /* ------------------------------------------------------------ */
    /** Add a JSON convertor.
     * Add a JSON convertor to the JSON instance used to convert
     * message fields.
     * @see JSON#addConvertorFor(String, org.eclipse.jetty.util.ajax.JSON.Convertor)
     */
    public static void addConvertorFor(String name,JSON.Convertor convertor)
    {
        __json.addConvertorFor(name,convertor);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static JSON __json=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new ImmutableHashMap<String, Object>().asMutable();
        }

        @Override
        protected String toString(char[] buffer, int offset, int length)
        {
            Map.Entry entry=__valueStrings.getEntry(buffer,offset,length);
            if (entry != null)
                return (String)entry.getValue();
            String s=new String(buffer,offset,length);
            return s;
        }

    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static JSON __msgJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new ServerMessageImpl().asMutable();
        }

        @Override
        protected String toString(char[] buffer, int offset, int length)
        {
            Map.Entry entry=__fieldStrings.getEntry(buffer,offset,length);
            if (entry != null)
                return (String)entry.getValue();

            String s=new String(buffer,offset,length);
            return s;
        }

        @Override
        protected JSON contextFor(String field)
        {
            return __json;
        }
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static JSON __batchJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new ServerMessageImpl().asMutable();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new ServerMessage.Mutable[size];
        }

        @Override
        protected JSON contextFor(String field)
        {
            return __json;
        }

        @Override
        protected JSON contextForArray()
        {
            return __msgJSON;
        }
    };

    /* ------------------------------------------------------------ */
    public static ServerMessage.Mutable[] parseMessages(Reader reader, boolean jsonDebug) throws ParseException, IOException
    {
        if (jsonDebug)
            return parseMessages(IO.toString(reader));

        try
        {
            Object batch=__batchJSON.parse(new JSON.ReaderSource(reader));
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

    /* ------------------------------------------------------------ */
    public static ServerMessage.Mutable[] parseMessages(String s) throws ParseException
    {
        try
        {
            Object batch=__batchJSON.parse(new JSON.StringSource(s));
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

    /* ------------------------------------------------------------ */
    public static ServerMessage.Mutable parseMessage(String s) throws IOException
    {
        return (ServerMessage.Mutable)__msgJSON.parse(new JSON.StringSource(s));
    }

}
