package org.cometd.server;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class ImmutableMessage extends AbstractMap<String,Object> implements Message, JSON.Generator
{
    private final ImmutableHashMap<String,Object> _immutable = new ImmutableHashMap<String, Object>(16)
    {
        @Override
        protected void onChange(String key) throws UnsupportedOperationException
        {
            _json=null;
        } ;
    };
    private final MutableMessage _mutable;
    private final Map.Entry<String,Object> _advice;
    private final Map.Entry<String,Object> _channelId;
    private final Map.Entry<String,Object> _clientId;
    private final Map.Entry<String,Object> _data;
    private final Map.Entry<String,Object> _ext;
    private final Map.Entry<String,Object> _id;


    private Message _associated;
    private String _json;
    private boolean _lazy=false;

    private final ImmutableMessagePool _pool;

    private final AtomicInteger _refs=new AtomicInteger();

    /* ------------------------------------------------------------ */
    public ImmutableMessage()
    {
        this(null);
    }

    /* ------------------------------------------------------------ */
    public ImmutableMessage(ImmutableMessagePool bayeux)
    {
        _pool=bayeux;

        _mutable = new MutableMessage();
        _advice=_immutable.getEntry(Message.ADVICE_FIELD);
        _channelId=_immutable.getEntry(Message.CHANNEL_FIELD);
        _clientId=_immutable.getEntry(Message.CLIENT_ID_FIELD);
        _data=_immutable.getEntry(Message.DATA_FIELD);
        _ext=_immutable.getEntry(Message.EXT_FIELD);
        _id=_immutable.getEntry(Message.ID_FIELD);

    }

    /* ------------------------------------------------------------ */
    public Message.Mutable asMutable()
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
            _mutable.clear();
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
        return (Map<String, Object>)_advice.getValue();
    }

    /* ------------------------------------------------------------ */
    public Message getAssociated()
    {
        return _associated;
    }

    /* ------------------------------------------------------------ */
    public String getChannelId()
    {
        return (String)_channelId.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getClientId()
    {
        return (String)_clientId.getValue();
    }

    /* ------------------------------------------------------------ */
    public Object getData()
    {
        return _data.getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getDataMap()
    {
        return (Map<String,Object>)_data.getValue();
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getExt()
    {
        return (Map<String, Object>)_ext.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return (String)_id.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getJSON()
    {
        if (_json == null)
        {
            JSON json=_pool == null?JSON.getDefault():_pool.getMsgJSON();
            StringBuffer buf=new StringBuffer(json.getStringBufferSize());
            synchronized(buf)
            {
                json.appendMap(buf,this);
                _json=buf.toString();
            }
        }
        return _json;
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
    public boolean isSuccessful()
    {
        Boolean bool=(Boolean)get(Message.SUCCESSFUL_FIELD);
        return bool != null && bool.booleanValue();
    }

    /* ------------------------------------------------------------ */
    public void setAssociated(Message associated)
    {
        if (_associated != associated)
        {
            if (_associated != null)
                ((ImmutableMessage)_associated).decRef();
            _associated=associated;
            if (_associated != null)
                ((ImmutableMessage)_associated).incRef();
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
        return getJSON();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    class MutableMessage extends AbstractMap<String,Object> implements Message.Mutable
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
            _mutable.put(Message.CLIENT_ID_FIELD,null);
            _mutable.put(Message.DATA_FIELD,null);
            _mutable.put(Message.EXT_FIELD,null);
            _mutable.put(Message.ID_FIELD,null);
            _advice=_mutable.getEntry(Message.ADVICE_FIELD);
            _channelId=_mutable.getEntry(Message.CHANNEL_FIELD);
            _clientId=_mutable.getEntry(Message.CLIENT_ID_FIELD);
            _data=_mutable.getEntry(Message.DATA_FIELD);
            _ext=_mutable.getEntry(Message.EXT_FIELD);
            _id=_mutable.getEntry(Message.ID_FIELD);
        }

        public ImmutableMessage asImmutable()
        {
            return ImmutableMessage.this;
        }

        @Override
        public void clear()
        {
            setAssociated(null);
            _refs.set(0);
            _json=null;
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
            return (Map<String, Object>)_advice.getValue();
        }

        public Map<String, Object> getMutableData()
        {
            Map<String, Object> data=(Map<String, Object>)_data.getValue();
            if (data==null)
            {
                data=new ImmutableHashMap<String,Object>().asMutable();
                _data.setValue(data);
            }
            return data;
        }

        public Map<String, Object> getMutableAdvice()
        {
            Map<String, Object> advice=(Map<String, Object>)_advice.getValue();
            if (advice==null)
            {
                advice=new ImmutableHashMap<String,Object>().asMutable();
                _advice.setValue(advice);
            }
            return advice;
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

        public Map<String,Object> getMutableExt()
        {
            Object ext=_ext.getValue();
            if (ext instanceof Map)
                return (Map<String,Object>)ext;

            if (ext instanceof JSON.Literal)
            {
                JSON json=_pool == null?JSON.getDefault():_pool.getMsgJSON();
                ext=json.fromJSON(ext.toString());
                _ext.setValue(ext);
                return (Map<String,Object>)ext;
            }

            ext=new HashMap<String,Object>();
            _ext.setValue(ext);
            return (Map<String,Object>)ext;
        }

        public String getId()
        {
            return (String)_id.getValue();
        }

        public boolean isLazy()
        {
            return _lazy;
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

        public Message getAssociated()
        {
            return ImmutableMessage.this.getAssociated();
        }

        public void setAssociated(Message message)
        {
            ImmutableMessage.this.setAssociated(message);
        }
    }

}
