package org.cometd.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.Bayeux;
import org.cometd.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class MessageImpl extends HashMap<String,Object> implements Message, JSON.Generator
{
    private MessagePool _pool;
    private Message _associated;
    private String _json;
    private ByteBuffer _buffer;
    private String _channel;
    private String _clientId;
    private String _id;
    private boolean _lazy;
    private Object _data;
    private Object _ext;
    private AtomicInteger _refs=new AtomicInteger();

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
    }

    /* ------------------------------------------------------------ */
    public void addJSON(Appendable buffer)
    {
        try
        {
            buffer.append(getJSON());
        }
        catch (IOException x)
        {
            throw new RuntimeException(x);
        }
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
        _buffer=null;
        _channel=null;
        _clientId=null;
        _data=null;
        _ext=null;
        _id=null;
        _json=null;
        _lazy=false;
        _ext=null; // TODO recycle
        _refs.set(0);

        Iterator<Map.Entry<String,Object>> iterator=super.entrySet().iterator();
        while(iterator.hasNext())
        {
            Map.Entry<String,Object> entry=iterator.next();
            String key=entry.getKey();
            if (Bayeux.CHANNEL_FIELD.equals(key))
                entry.setValue(null);
            else if (Bayeux.ID_FIELD.equals(key))
                entry.setValue(null);
            else if (Bayeux.TIMESTAMP_FIELD.equals(key))
                entry.setValue(null);
            else if (Bayeux.DATA_FIELD.equals(key))
                entry.setValue(null);
            else if (Bayeux.EXT_FIELD.equals(key))
                entry.setValue(null);
            else
                iterator.remove();
        }
        super.clear();
    }

    /* ------------------------------------------------------------ */
    public Object clone()
    {
        MessageImpl clone = (MessageImpl)super.clone();

        // Cloned instance will not be pooled
        clone._pool = null;

        // Adjust refs for the associated message
        if (clone._associated instanceof MessageImpl)
            ((MessageImpl)clone._associated).incRef();

        // Reset cached values
        clone._json = null;
        clone._buffer = null;

        // Plain fields _channel, _clientId, _id, _lazy have been already shallow cloned

        // Fields _data and _ext cannot be deep cloned, because even if they implement
        // java.lang.Cloneable, we cannot call clone() because it is a protected method.
        // Object.clone() is really broken !

        // Cloned instance has a different reference counter
        clone._refs = new AtomicInteger();

        return clone;
    }

    /* ------------------------------------------------------------ */
    public void decRef()
    {
        int r=_refs.decrementAndGet();
        if (r == 0 && _pool != null)
        {
            setAssociated(null);
            _pool.recycleMessage(this);
        }
        else if (r < 0)
            throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see java.util.HashMap#entrySet()
     */
    @Override
    public Set<java.util.Map.Entry<String,Object>> entrySet()
    {
        return Collections.unmodifiableSet(super.entrySet());
    }

    /* ------------------------------------------------------------ */
    public Message getAssociated()
    {
        return _associated;
    }

    /* ------------------------------------------------------------ */
    public ByteBuffer getBuffer()
    {
        return _buffer;
    }

    /* ------------------------------------------------------------ */
    public String getChannel()
    {
        return _channel;
    }

    /* ------------------------------------------------------------ */
    public String getClientId()
    {
        if (_clientId == null)
            _clientId=(String)get(Bayeux.CLIENT_FIELD);
        return _clientId;
    }

    /* ------------------------------------------------------------ */
    public Object getData()
    {
        return _data;
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getExt(boolean create)
    {
        Object ext=_ext == null?get(Bayeux.EXT_FIELD):_ext;
        if (ext instanceof Map)
            return (Map<String,Object>)ext;
        if (ext instanceof JSON.Literal)
        {
            JSON json=_pool == null?JSON.getDefault():_pool.getMsgJSON();
            _ext=ext=json.fromJSON(ext.toString());
            super.put(Bayeux.EXT_FIELD,ext);
            return (Map<String,Object>)ext;
        }

        if (!create)
            return null;

        _ext=ext=new HashMap<String,Object>();
        super.put(Bayeux.EXT_FIELD,ext);
        return (Map<String,Object>)ext;

    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return _id;
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
        Boolean bool=(Boolean)get(Bayeux.SUCCESSFUL_FIELD);
        return bool != null && bool.booleanValue();
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see java.util.HashMap#keySet()
     */
    @Override
    public Set<String> keySet()
    {
        return Collections.unmodifiableSet(super.keySet());
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see java.util.HashMap#put(java.lang.Object, java.lang.Object)
     */
    @Override
    public Object put(String key, Object value)
    {
        _json=null;
        _buffer=null;
        if (Bayeux.CHANNEL_FIELD.equals(key))
            _channel=(String)value;
        else if (Bayeux.ID_FIELD.equals(key))
            _id=value.toString();
        else if (Bayeux.CLIENT_FIELD.equals(key))
            _clientId=(String)value;
        else if (Bayeux.DATA_FIELD.equals(key))
            _data=value;
        else if (Bayeux.EXT_FIELD.equals(key))
            _ext=value;
        return super.put(key,value);
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see java.util.HashMap#putAll(java.util.Map)
     */
    @Override
    public void putAll(Map<? extends String,? extends Object> m)
    {
        _json=null;
        _buffer=null;
        super.putAll(m);
        _channel=(String)get(Bayeux.CHANNEL_FIELD);
        Object id=get(Bayeux.ID_FIELD);
        _id=id == null?null:id.toString();
        _data=get(Bayeux.DATA_FIELD);
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see java.util.HashMap#remove(java.lang.Object)
     */
    @Override
    public Object remove(Object key)
    {
        _json=null;
        _buffer=null;
        if (Bayeux.CHANNEL_FIELD.equals(key))
            _channel=null;
        else if (Bayeux.ID_FIELD.equals(key))
            _id=null;
        else if (Bayeux.DATA_FIELD.equals(key))
            _data=null;
        else if (Bayeux.EXT_FIELD.equals(key))
            _ext=null;
        return super.remove(key);
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
    /**
     * @param buffer
     *            A cached buffer containing HTTP response headers and message
     *            content, to be reused when sending one message to multiple
     *            clients
     */
    public void setBuffer(ByteBuffer buffer)
    {
        _buffer=buffer;
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
