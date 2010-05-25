package org.cometd.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class HashMapMessage extends HashMap<String,Object> implements Message.Mutable, JSON.Generator
{
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static JSON __json=new JSON()
    {
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static JSON __msgJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new HashMapMessage();
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
            return new HashMapMessage();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new Message[size];
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

    private Message _associated;
    private boolean _lazy=false;


    /* ------------------------------------------------------------ */
    public HashMapMessage()
    {
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
    public void clear()
    {
        _lazy=false;
        super.clear();
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getAdvice()
    {
        return (Map<String, Object>)get(ADVICE_FIELD);
    }

    /* ------------------------------------------------------------ */
    public Message getAssociated()
    {
        return _associated;
    }

    /* ------------------------------------------------------------ */
    public String getChannel()
    {
        return (String)get(CHANNEL_FIELD);
    }

    /* ------------------------------------------------------------ */
    public String getClientId()
    {
        return (String)get(CLIENT_ID_FIELD);
    }

    /* ------------------------------------------------------------ */
    public Object getData()
    {
        return get(DATA_FIELD);
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getDataAsMap()
    {
        return (Map<String,Object>)get(DATA_FIELD);
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getExt()
    {
        return (Map<String, Object>)get(EXT_FIELD);
    }

    /* ------------------------------------------------------------ */
    public String getId()
    {
        return (String)get(ID_FIELD);
    }

    /* ------------------------------------------------------------ */
    public String getJSON()
    {
        StringBuffer buf=new StringBuffer(__json.getStringBufferSize());
        synchronized(buf)
        {
            __json.appendMap(buf,this);
            return buf.toString();
        }
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getAdvice(boolean create)
    {
        Map<String, Object> advice=getAdvice();
        if (create && advice==null)
        {
            advice = new HashMap<String,Object>();
            put(ADVICE_FIELD,advice);
        }
        return advice;
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getDataAsMap(boolean create)
    {
        Map<String, Object> data=(Map<String,Object>)getData();
        if (create && data==null)
        {
            data = new HashMap<String,Object>();
            put(DATA_FIELD,data);
        }
        return data;
    }

    /* ------------------------------------------------------------ */
    public Map<String,Object> getExt(boolean create)
    {
        Object ext=getExt();
        if (ext==null && !create)
            return null;

        if (ext instanceof Map)
            return (Map<String,Object>)ext;

        if (ext instanceof JSON.Literal)
        {
            ext=__json.fromJSON(ext.toString());
            put(EXT_FIELD,ext);
            return (Map<String,Object>)ext;
        }

        ext=new HashMap<String,Object>();
        put(EXT_FIELD,ext);
        return (Map<String,Object>)ext;
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
    /**
     * @return True if the message is for a Meta channel
     */
    public boolean isMeta()
    {
        return ChannelId.isMeta(getChannel());
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
        _associated=associated;
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

    public void setChannel(String channel)
    {
        put(CHANNEL_FIELD, channel);
    }

    public void setClientId(String clientId)
    {
        put(CLIENT_ID_FIELD,clientId);
    }

    public void setData(Object data)
    {
        put(DATA_FIELD,data);
    }

    public void setId(String id)
    {
        put(ID_FIELD,id);
    }

    public void setSuccessful(boolean successful)
    {
        put(SUCCESSFUL_FIELD, successful ?Boolean.TRUE:Boolean.FALSE);
    }


    public Message asImmutable()
    {
        return this;
    }

}
