package org.cometd.server;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.ChannelId;
import org.cometd.util.ImmutableHashMap;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessageImpl extends AbstractMap<String,Object> implements ServerMessage, JSON.Generator
{
    private final ImmutableHashMap<String,Object> _immutable = new NestedMap(8);
    private final MutableMessage _mutable;
    private final Map.Entry<String,Object> _advice;
    private final Map.Entry<String,Object> _data;
    private final Map.Entry<String,Object> _ext;
    

    private ServerMessage _associated;
    private String _jsonString;
    private boolean _lazy=false;
    
    private final ServerMessagePool _pool;
    

    private final AtomicInteger _refs=new AtomicInteger();

    /* ------------------------------------------------------------ */
    public ServerMessageImpl()
    {
        this(null);
    }
    
    /* ------------------------------------------------------------ */
    public ServerMessageImpl(ServerMessagePool bayeux)
    {
        _pool=bayeux;
        _mutable = new MutableMessage();
        _advice=_immutable.getEntry(Message.ADVICE_FIELD);
        _data=_immutable.getEntry(Message.DATA_FIELD);
        _ext=_immutable.getEntry(Message.EXT_FIELD);
    }

    /* ------------------------------------------------------------ */
    public ServerMessage.Mutable asMutable()
    {
        return _mutable;
    }

    /* ------------------------------------------------------------ */
    public void addJSON(StringBuffer buffer)
    {
        buffer.append(getJSON());
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
    public void decRef()
    {
        int r=_refs.decrementAndGet();
        if (r == 0 && _pool != null)
        {
            _pool.recycleMessage(this);
        }
        else if (r < 0)
            throw new IllegalStateException();
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
        Object advice=_mutable._advice.getValue();
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
    public String getChannelId()
    {
        return (String)_mutable._channelId.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getClientId()
    {
        return (String)_mutable._clientId.getValue();
    }

    /* ------------------------------------------------------------ */
    public Object getData()
    {
        return _data.getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getDataAsMap()
    {
        return (Map<String,Object>)_data.getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getExt()
    {
        return (Map<String, Object>)_ext.getValue();
    }

    /* ------------------------------------------------------------ */
    public Object getId()
    {
        return _mutable._id.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getJSON()
    {
        if (_jsonString == null)
        {
            JSON json=_pool == null?JSON.getDefault():_pool.getMsgJSON();
            StringBuffer buf=new StringBuffer(json.getStringBufferSize());
            synchronized(buf)
            {
                json.appendMap(buf,this);
                _jsonString=buf.toString();
            }
        }
        return _jsonString;
    }

    /* ------------------------------------------------------------ */
    public int getRefs()
    {
        return _refs.get();
    }

    /* ------------------------------------------------------------ */
    public void incRef()
    {
        _refs.incrementAndGet();
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
        return ChannelId.isMeta((String)_mutable._channelId.getValue());
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
        if (_associated != associated)
        {
            if (_associated != null)
                _associated.decRef();
            _associated=associated;
            if (_associated != null)
                _associated.incRef();
        }
    }

    /* ------------------------------------------------------------ */
    public void setData(Object data)
    {
        _data.setValue(data);
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
        private final Map.Entry<String,Object> _advice;
        private final Map.Entry<String,Object> _channelId;
        private final Map.Entry<String,Object> _clientId;
        private final Map.Entry<String,Object> _data;
        private final Map.Entry<String,Object> _ext;
        private final Map.Entry<String,Object> _id;
        
        MutableMessage()
        {
            _mutable.put(Message.ADVICE_FIELD,null);
            _mutable.put(Message.CHANNEL_FIELD,null);
            _mutable.put(Message.CLIENT_FIELD,null);
            _mutable.put(Message.DATA_FIELD,null);
            _mutable.put(Message.EXT_FIELD,null);
            _mutable.put(Message.ID_FIELD,null);
            _advice=_mutable.getEntry(Message.ADVICE_FIELD);
            _channelId=_mutable.getEntry(Message.CHANNEL_FIELD);
            _clientId=_mutable.getEntry(Message.CLIENT_FIELD);
            _data=_mutable.getEntry(Message.DATA_FIELD);
            _ext=_mutable.getEntry(Message.EXT_FIELD);
            _id=_mutable.getEntry(Message.ID_FIELD);
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
                data=new NestedMap(16).asMutable();
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
                advice=new NestedMap(16).asMutable();;
                _advice.setValue(advice);
            }
            return (Map<String, Object>)advice;
        }

        public String getChannelId()
        {
            return (String)_channelId.getValue();
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
                JSON json=_pool == null?JSON.getDefault():_pool.getMsgJSON();
                ext=json.fromJSON(ext.toString());
                _ext.setValue(ext);
                return (Map<String,Object>)ext;
            }

            ext=new NestedMap();
            _ext.setValue(ext);
            return (Map<String,Object>)ext;
        }

        public Object getId()
        {
            return _id.getValue();
        }

        public boolean isLazy()
        {
            return _lazy;
        }

        public boolean isMeta()
        {
            return ChannelId.isMeta((String)_channelId.getValue());
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

        public void setId(Object id)
        {
            _id.setValue(id);
        }

        public void setChannelId(String channelId)
        {
            _channelId.setValue(channelId);
        }

        public String getJSON()
        {
            return ServerMessageImpl.this.getJSON();
        }

        public void decRef()
        {
            ServerMessageImpl.this.decRef();
        }

        public void incRef()
        {
            ServerMessageImpl.this.incRef();
        }

        public boolean isSuccessful()
        {
            return ServerMessageImpl.this.isSuccessful();
        }

        public void setSuccessful(boolean success)
        {
            put(SUCCESSFUL_FIELD,success?Boolean.TRUE:Boolean.FALSE);
        }
        
        public String toString()
        {
            return getJSON();
        }
    }

    class NestedMap extends ImmutableHashMap<String,Object>
    {
        protected NestedMap()
        {
        }
        
        protected NestedMap(int size)
        {
            super(size);
        }
        
        @Override
        protected void onChange(String key) throws UnsupportedOperationException
        {
            _jsonString=null;
        } ;
    };
}
