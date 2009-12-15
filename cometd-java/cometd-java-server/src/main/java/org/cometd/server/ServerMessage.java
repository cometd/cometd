package org.cometd.server;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerMessage extends AbstractMap<String,Object> implements Message, JSON.Generator
{
    
    final ImmutableHashMap<String,Object> _immutable = new ImmutableHashMap<String, Object>(16)
    {
        @Override
        protected void change(String key) throws UnsupportedOperationException
        {
            _json=null;
        } ;
    };
    final MutableMessage _mutable = new MutableMessage();
    final Map.Entry<String,Object> _advice;
    final Map.Entry<String,Object> _channelId;
    final Map.Entry<String,Object> _clientId;
    final Map.Entry<String,Object> _data;
    final Map.Entry<String,Object> _ext;
    final Map.Entry<String,Object> _id;
    

    Message _associated;
    String _json;
    boolean _lazy=false;
    
    final MessagePool _pool;
    

    final AtomicInteger _refs=new AtomicInteger();

    /* ------------------------------------------------------------ */
    public ServerMessage()
    {
        this(null);
    }
    
    /* ------------------------------------------------------------ */
    public ServerMessage(MessagePool bayeux)
    {
        _pool=bayeux;

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
    public boolean containsKey(Object key)
    {
        return _immutable.containsKey(key);
    }

    /* ------------------------------------------------------------ */
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
            clear();
            _pool.recycleMessage(this);
        }
        else if (r < 0)
            throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    public Set<java.util.Map.Entry<String, Object>> entrySet()
    {
        return _immutable.entrySet();
    }

    /* ------------------------------------------------------------ */
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
                ((ServerMessage)_associated).decRef();
            _associated=associated;
            if (_associated != null)
                ((ServerMessage)_associated).incRef();
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
        final ImmutableHashMap<String,Object>.Mutable _mutable=_immutable.asMutable();

        public Message asImmutable()
        {
            return ServerMessage.this;
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

        public boolean containsKey(Object key)
        {
            return _mutable.containsKey(key);
        }

        public Set entrySet()
        {
            return _mutable.entrySet();
        }

        public Object get(Object key)
        {
            return _mutable.get(key);
        }

        public Map<String, Object> getAdvice()
        {
            return (Map<String, Object>)_advice.getValue();
        }

        public Map<String, Object> getAdvice(boolean create)
        {
            if (_advice.getValue()==null && create)
                _advice.setValue(new ImmutableHashMap<String,Object>().asMutable());
            return (Map<String, Object>)_advice.getValue();
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
            return getExt(false);
        }

        public Map<String,Object> getExt(boolean create)
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

            if (!create)
                return null;

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

        public Object put(String key, Object value)
        {
            return _mutable.put(key,value);
        }

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

        public int size()
        {
            return _mutable.size();
        }
    }
    
}
