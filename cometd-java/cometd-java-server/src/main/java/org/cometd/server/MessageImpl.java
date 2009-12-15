package org.cometd.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class MessageImpl extends ImmutableHashMap<String,Object> implements Message.Mutable, JSON.Generator
{
    final Map.Entry<String,Object> _advice;
    Message _associated;

    final Map.Entry<String,Object> _channelId;
    final Map.Entry<String,Object> _clientId;
    final Map.Entry<String,Object> _data;
    final Map.Entry<String,Object> _ext;
    final Map.Entry<String,Object> _id;
    String _json;
    
    boolean _lazy=false;
    final MessagePool _pool;
    final AtomicInteger _refs=new AtomicInteger();

    /* ------------------------------------------------------------ */
    public MessageImpl()
    {
        this(null);
    }

    /* ------------------------------------------------------------ */
    public MessageImpl(MessagePool bayeux)
    {
        super(8);
        _pool=bayeux;

        super.put(Message.ADVICE_FIELD,null);
        super.put(Message.CHANNEL_FIELD,null);
        super.put(Message.CLIENT_FIELD,null);
        super.put(Message.DATA_FIELD,null);
        super.put(Message.EXT_FIELD,null);
        super.put(Message.ID_FIELD,null);
        _advice=super.getEntry(Message.ADVICE_FIELD);
        _channelId=super.getEntry(Message.CHANNEL_FIELD);
        _clientId=super.getEntry(Message.CLIENT_FIELD);
        _data=super.getEntry(Message.DATA_FIELD);
        _ext=super.getEntry(Message.EXT_FIELD);
        _id=super.getEntry(Message.ID_FIELD);
    }
    
    /* ------------------------------------------------------------ */
    public void addJSON(StringBuffer buffer)
    {
        buffer.append(getJSON());
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void change(String key) throws UnsupportedOperationException
    {
        _json=null;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * 
     * @see java.util.HashMap#clear()
     */
    @Override
    public void clear()
    {
        setAssociated(null);
        _refs.set(0);
        _json=null;
        _lazy=false;
        super.clear();
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
    public Map<String, Object> getAdvice()
    {
        return getAdvice(false);
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getAdvice(boolean create)
    {
        if (_advice.getValue()==null)
            _advice.setValue(new HashMap<String,Object>());
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
        return getExt(false);
    }
    
    /* ------------------------------------------------------------ */
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
                ((MessageImpl)_associated).decRef();
            _associated=associated;
            if (_associated != null)
                ((MessageImpl)_associated).incRef();
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
    public String toString()
    {
        return getJSON();
    }

}
