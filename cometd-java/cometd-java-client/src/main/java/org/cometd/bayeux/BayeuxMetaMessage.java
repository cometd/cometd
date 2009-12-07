package org.cometd.bayeux;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import bayeux.MetaChannel;
import bayeux.MetaMessage;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxMetaMessage implements MetaMessage, JSON.Convertible
{
    private static final AtomicInteger ids = new AtomicInteger();

    private final Map<String, Object> fields = new HashMap<String, Object>();
    private final MetaChannel metaChannel;
    private String id;
    private String clientId;
    private Map<String, Object> ext;

    public BayeuxMetaMessage(MetaChannel metaChannel)
    {
        this.metaChannel = metaChannel;
    }

    public String getId()
    {
        return id;
    }

    public String getClientId()
    {
        return clientId;
    }

    public MetaChannel getMetaChannel()
    {
        return metaChannel;
    }

    public void put(String name, Object value)
    {
        fields.put(name, value);
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
        output.add("id", id == null ? nextId() : id);
        output.add("channel", metaChannel.getName());
        output.add(fields);
        if (ext != null)
            output.add(ext);
    }

    public void fromJSON(Map object)
    {
    }
}
