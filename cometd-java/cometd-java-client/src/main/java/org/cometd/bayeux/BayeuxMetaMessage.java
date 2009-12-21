package org.cometd.bayeux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.client.MetaChannel;
import org.cometd.bayeux.client.MetaMessage;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxMetaMessage implements MetaMessage.Mutable, JSON.Convertible
{
    private static final AtomicInteger ids = new AtomicInteger();

    private final Map<String, Object> fields;
    private MetaChannel metaChannel;
    private Map<String, Object> ext;
    private MetaMessage associated;

    public BayeuxMetaMessage()
    {
        this(new HashMap<String, Object>());
    }

    public BayeuxMetaMessage(Map<String, Object> fields)
    {
        this.fields = fields;
    }

    public MetaMessage getAssociated()
    {
        return associated;
    }

    public void setAssociated(MetaMessage associated)
    {
        this.associated = associated;
    }

    public Object get(String field)
    {
        return fields.get(field);
    }

    public void put(String name, Object value)
    {
        fields.put(name, value);
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

    public MetaChannel getMetaChannel()
    {
        return metaChannel;
    }

    public void setMetaChannel(MetaChannel metaChannel)
    {
        this.metaChannel = metaChannel;
    }

    public Map<String, Object> getExt(boolean create)
    {
        if (ext == null && create)
        {
            ext = new HashMap<String, Object>();
        }
        return ext;
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
        output.add(fields);
        if (ext != null)
            output.add(ext);
    }

    public void fromJSON(Map object)
    {

    }
}
