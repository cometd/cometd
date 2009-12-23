package org.cometd.bayeux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class StandardMessage extends StandardStruct implements IMessage.Mutable, JSON.Convertible
{
    private static final AtomicInteger ids = new AtomicInteger();

    // TODO: this constructor is only needed until we fix the JSON conversion
    // TODO: so that we don't do json to map to message but directly json to message
    public StandardMessage(Map<String, Object> fields)
    {
        if (fields != null)
            putAll(fields);
    }

    public boolean isSuccessful()
    {
        return (Boolean)get(Message.SUCCESSFUL_FIELD);
    }

    public void setSuccessful(boolean value)
    {
        put(Message.SUCCESSFUL_FIELD, value);
    }

    public String getId()
    {
        return (String)get(Message.ID_FIELD);
    }

    public void setId(String id)
    {
        put(Message.ID_FIELD, id);
    }

    public String getClientId()
    {
        return (String)get(Message.CLIENT_ID_FIELD);
    }

    public void setClientId(String clientId)
    {
        put(Message.CLIENT_ID_FIELD, clientId);
    }

    public String getChannelName()
    {
        return (String)get(Message.CHANNEL_FIELD);
    }

    public void setChannelName(String channelName)
    {
        put(Message.CHANNEL_FIELD, channelName);
    }

    public Struct.Mutable getAdvice()
    {
        return (Struct.Mutable)get(Message.ADVICE_FIELD);
    }

    public void setAdvice(Struct.Mutable advice)
    {
        put(Message.ADVICE_FIELD, advice);
    }

    public Struct getExt(boolean create)
    {
        Struct ext = (Struct)get(Message.EXT_FIELD);
        if (ext == null && create)
        {
            setExt(new StandardStruct());
            ext = getExt(false);
        }
        return ext;
    }

    public void setExt(Struct.Mutable ext)
    {
        put(Message.EXT_FIELD, ext);
    }

    protected static String nextId()
    {
        return String.valueOf(ids.incrementAndGet());
    }

    public Object getData()
    {
        return get(Message.DATA_FIELD);
    }

    public void setData(Object data)
    {
        put(Message.DATA_FIELD, data);
    }

    public boolean isLazy()
    {
        return false;
    }

    public void setLazy(boolean lazy)
    {
    }

    public Message getAssociated()
    {
        return null;
    }

    public void setAssociated(Message message)
    {
    }

    public void toJSON(JSON.Output output)
    {
        String id = getId();
        if (id == null)
            output.add("id", nextId());
        output.add(this);
    }

    public void fromJSON(Map object)
    {
        // TODO
    }
}
