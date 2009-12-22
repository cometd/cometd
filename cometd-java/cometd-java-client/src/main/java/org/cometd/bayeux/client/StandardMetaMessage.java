package org.cometd.bayeux.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.StandardStruct;
import org.cometd.bayeux.Struct;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class StandardMetaMessage extends StandardStruct implements MetaMessage.Mutable, JSON.Convertible
{
    private static final AtomicInteger ids = new AtomicInteger();

    private MetaChannel metaChannel;

    public StandardMetaMessage()
    {
    }

    public StandardMetaMessage(Map<String, Object> fields)
    {
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

    public Struct.Mutable getAdvice()
    {
        return (Struct.Mutable)get(Message.ADVICE_FIELD);
    }

    public void setAdvice(Struct.Mutable advice)
    {
        put(Message.ADVICE_FIELD, advice);
    }

    public MetaChannel getMetaChannel()
    {
        return metaChannel;
    }

    public void setMetaChannel(MetaChannel metaChannel)
    {
        this.metaChannel = metaChannel;
    }

    protected static String nextId()
    {
        return String.valueOf(ids.incrementAndGet());
    }

    public void toJSON(JSON.Output output)
    {
        String id = getId();
        output.add("id", id == null ? nextId() : id);
        output.add("channel", metaChannel.getType().getName());
        output.add(this);
    }

    public void fromJSON(Map object)
    {
        // TODO
    }
}
