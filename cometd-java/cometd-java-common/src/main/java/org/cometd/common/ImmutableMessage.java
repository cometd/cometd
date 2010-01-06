package org.cometd.common;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.util.ImmutableHashMap;
import org.eclipse.jetty.util.ajax.JSON;

public class ImmutableMessage extends AbstractMap<String,Object> implements Message, JSON.Generator
{
    private final ImmutableHashMap<String,Object> _immutable = new ImmutableHashMap<String, Object>(16)
    {
        @Override
        protected void onChange(String key) throws UnsupportedOperationException
        {
            _jsonString=null;
        } ;
    };
    protected final MutableMessage _mutable;
    protected final Map.Entry<String,Object> _advice;
    protected final Map.Entry<String,Object> _data;
    protected final Map.Entry<String,Object> _ext;
    protected String _jsonString;

    /* ------------------------------------------------------------ */
    public ImmutableMessage()
    {
        _mutable = new MutableMessage();
        _advice=_immutable.getEntry(Message.ADVICE_FIELD);
        _data=_immutable.getEntry(Message.DATA_FIELD);
        _ext=_immutable.getEntry(Message.EXT_FIELD);
    }

    /* ------------------------------------------------------------ */
    protected ImmutableMessage(MutableMessage mutable)
    {
        _mutable = mutable;
        _advice=_immutable.getEntry(Message.ADVICE_FIELD);
        _data=_immutable.getEntry(Message.DATA_FIELD);
        _ext=_immutable.getEntry(Message.EXT_FIELD);
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
    public String getId()
    {
        return (String)_mutable._id.getValue();
    }

    /* ------------------------------------------------------------ */
    public String getJSON()
    {
        if (_jsonString == null)
        {
            final JSON json=JSON.getDefault();
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
    public boolean isSuccessful()
    {
        Boolean bool=(Boolean)get(Message.SUCCESSFUL_FIELD);
        return bool != null && bool.booleanValue();
    }

    /* ------------------------------------------------------------ */
    public void setData(Object data)
    {
        _data.setValue(data);
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
    protected class MutableMessage extends AbstractMap<String,Object> implements Message.Mutable
    {
        protected final ImmutableHashMap<String,Object>.Mutable _mutable=_immutable.asMutable();
        protected final Map.Entry<String,Object> _advice;
        protected final Map.Entry<String,Object> _channelId;
        protected final Map.Entry<String,Object> _clientId;
        protected final Map.Entry<String,Object> _data;
        protected final Map.Entry<String,Object> _ext;
        protected final Map.Entry<String,Object> _id;
        
        protected MutableMessage()
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
        
        public ImmutableMessage asImmutable()
        {
            return ImmutableMessage.this;
        }

        @Override
        public void clear()
        {
            _jsonString=null;
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
                data=new ImmutableHashMap<String,Object>().asMutable();
                _data.setValue(data);
            }
            return data;
        }

        public Map<String, Object> getAdvice(boolean create)
        {
            Map<String, Object> advice=(Map<String, Object>)_advice.getValue();
            if (create && advice==null)
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

        public Map<String,Object> getExt(boolean create)
        {
            Object ext=_ext.getValue();
            if (ext==null && !create)
                return null;
            
            if (ext instanceof Map)
                return (Map<String,Object>)ext;
            
            if (ext instanceof JSON.Literal)
            {
                JSON json=JSON.getDefault();
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

        @Override
        public int size()
        {
            return _mutable.size();
        }

        public void setClientId(String clientId)
        {
            _clientId.setValue(clientId);
        }

        public void setId(String id)
        {
            _id.setValue(id);
        }

        public void setChannelId(String channelId)
        {
            _channelId.setValue(channelId);
        }

        public String getJSON()
        {
            return ImmutableMessage.this.getJSON();
        }
    }
    
}
