package org.cometd.bayeux;

import java.util.HashMap;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public enum MetaChannelType
{
    HANDSHAKE("/meta/handshake"),
    CONNECT("/meta/connect"),
    SUBSCRIBE("/meta/subscribe"),
    UNSUBSCRIBE("/meta/unsubscribe"),
    PUBLISH("/meta/publish"),
    DISCONNECT("/meta/disconnect");

    public static MetaChannelType from(String name)
    {
        return MetaChannelTypes.values.get(name);
    }

    private final String name;

    private MetaChannelType(String name)
    {
        this.name = name;
        MetaChannelTypes.values.put(name, this);
    }

    public String getName()
    {
        return name;
    }

    private static class MetaChannelTypes
    {
        private static final Map<String, MetaChannelType> values = new HashMap<String, MetaChannelType>();
    }
}
