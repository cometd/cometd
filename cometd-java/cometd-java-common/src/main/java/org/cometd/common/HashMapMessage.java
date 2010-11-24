package org.cometd.common;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.JSON;

public class HashMapMessage extends HashMap<String, Object> implements Message.Mutable, JSON.Generator, Serializable
{
    private static final long serialVersionUID = 4318697940670212190L;

    public HashMapMessage()
    {
    }

    public HashMapMessage(Message message)
    {
        putAll(message);
    }

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
        Object advice = get(ADVICE_FIELD);
        if (advice instanceof JSON.Literal)
        {
            advice = jsonParser.parse(advice.toString());
            put(ADVICE_FIELD, advice);
        }
        return (Map<String, Object>)advice;
    }

    public String getChannel()
    {
        return (String)get(CHANNEL_FIELD);
    }

    public ChannelId getChannelId()
    {
        return new ChannelId(getChannel());
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
        Object data = get(DATA_FIELD);
        if (data instanceof JSON.Literal)
        {
            data = jsonParser.parse(data.toString());
            put(DATA_FIELD, data);
        }
        return (Map<String, Object>)data;
    }

    public Map<String, Object> getExt()
    {
        Object ext = get(EXT_FIELD);
        if (ext instanceof JSON.Literal)
        {
            ext = jsonParser.parse(ext.toString());
            put(EXT_FIELD, ext);
        }
        return (Map<String, Object>)ext;
    }

    public String getId()
    {
        // Support also old-style ids of type long
        Object id = get(ID_FIELD);
        return id == null ? null : String.valueOf(id);
    }

    public String getJSON()
    {
        Appendable buf = new StringBuilder(jsonParser.getStringBufferSize());
        jsonParser.appendMap(buf, this);
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
        Map<String, Object> data = getDataAsMap();
        if (create && data == null)
        {
            data = new HashMap<String, Object>();
            put(DATA_FIELD, data);
        }
        return data;
    }

    public Map<String, Object> getExt(boolean create)
    {
        Map<String, Object> ext = getExt();
        if (create && ext == null)
        {
            ext = new HashMap<String, Object>();
            put(EXT_FIELD, ext);
        }
        return ext;
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
        if (channel==null)
            remove(CHANNEL_FIELD);
        else
            put(CHANNEL_FIELD, channel);
    }

    public void setClientId(String clientId)
    {
        if (clientId==null)
            remove(CLIENT_ID_FIELD);
        else
            put(CLIENT_ID_FIELD, clientId);
    }

    public void setData(Object data)
    {
        if (data==null)
            remove(DATA_FIELD);
        else
            put(DATA_FIELD, data);
    }

    public void setId(String id)
    {
        if (id==null)
            remove(ID_FIELD);
        else
            put(ID_FIELD, id);
    }

    public void setSuccessful(boolean successful)
    {
        put(SUCCESSFUL_FIELD, successful);
    }

    public static List<Mutable> parseMessages(String content)
    {
        Object object = messagesParser.parse(new JSON.StringSource(content));
        if (object instanceof Message.Mutable)
            return Collections.singletonList((Message.Mutable)object);
        return Arrays.asList((Message.Mutable[])object);
    }

    protected static JSON jsonParser = new JSON();
    private static JSON _messageParser = new JSON()
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return new HashMapMessage();
        }

        @Override
        protected JSON contextFor(String field)
        {
            return jsonParser;
        }
    };
    private static JSON messagesParser = new JSON()
    {
        @Override
        protected Map<String, Object> newMap()
        {
            return new HashMapMessage();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new Message.Mutable[size];
        }

        @Override
        protected JSON contextFor(String field)
        {
            return jsonParser;
        }

        @Override
        protected JSON contextForArray()
        {
            return _messageParser;
        }
    };
}
