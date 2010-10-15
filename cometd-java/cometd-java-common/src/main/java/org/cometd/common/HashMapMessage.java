package org.cometd.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class HashMapMessage extends HashMap<String, Object> implements Message.Mutable, JSON.Generator
{
    private static JSON __json = new JSON();

    public void addJSON(Appendable buffer)
    {
        try
        {
            buffer.append(getJSON());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> getAdvice()
    {
        return (Map<String, Object>)get(ADVICE_FIELD);
    }

    public String getChannel()
    {
        return (String)get(CHANNEL_FIELD);
    }

    public String getClientId()
    {
        return (String)get(CLIENT_ID_FIELD);
    }

    public Object getData()
    {
        return get(DATA_FIELD);
    }

    public Map<String, Object> getDataAsMap()
    {
        return (Map<String, Object>)get(DATA_FIELD);
    }

    public Map<String, Object> getExt()
    {
        return (Map<String, Object>)get(EXT_FIELD);
    }

    public String getId()
    {
        return (String)get(ID_FIELD);
    }

    public String getJSON()
    {
        Appendable buf = new StringBuilder(__json.getStringBufferSize());
        __json.appendMap(buf, this);
        return buf.toString();
    }

    public Map<String, Object> getAdvice(boolean create)
    {
        Map<String, Object> advice = getAdvice();
        if (create && advice == null)
        {
            advice = new HashMap<String, Object>();
            put(ADVICE_FIELD, advice);
        }
        return advice;
    }

    public Map<String, Object> getDataAsMap(boolean create)
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)getData();
        if (create && data == null)
        {
            data = new HashMap<String, Object>();
            put(DATA_FIELD, data);
        }
        return data;
    }

    public Map<String, Object> getExt(boolean create)
    {
        Object ext = getExt();
        if (ext == null && !create)
            return null;

        if (ext instanceof Map)
            return (Map<String, Object>)ext;

        if (ext instanceof JSON.Literal)
        {
            ext = __json.fromJSON(ext.toString());
            put(EXT_FIELD, ext);
            return (Map<String, Object>)ext;
        }

        ext = new HashMap<String, Object>();
        put(EXT_FIELD, ext);
        return (Map<String, Object>)ext;
    }

    public boolean isMeta()
    {
        return ChannelId.isMeta(getChannel());
    }

    public boolean isSuccessful()
    {
        Boolean value = (Boolean)get(Message.SUCCESSFUL_FIELD);
        return value != null && value;
    }

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
        put(CLIENT_ID_FIELD, clientId);
    }

    public void setData(Object data)
    {
        put(DATA_FIELD, data);
    }

    public void setId(String id)
    {
        put(ID_FIELD, id);
    }

    public void setSuccessful(boolean successful)
    {
        put(SUCCESSFUL_FIELD, successful);
    }
}
