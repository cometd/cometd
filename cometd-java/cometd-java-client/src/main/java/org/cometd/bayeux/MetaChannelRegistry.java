package org.cometd.bayeux;

import java.util.EnumMap;

import org.cometd.bayeux.client.MetaChannel;
import org.cometd.bayeux.client.MetaChannelType;

/**
 * @version $Revision$ $Date$
 */
public class MetaChannelRegistry
{
    private final EnumMap<MetaChannelType, MetaChannel.Mutable> metaChannels = new EnumMap<MetaChannelType, MetaChannel.Mutable>(MetaChannelType.class);

    public MetaChannelRegistry()
    {
        metaChannels.put(MetaChannelType.HANDSHAKE, new StandardMetaChannel(MetaChannelType.HANDSHAKE));
        // TODO: add other channels

    }

    public MetaChannel.Mutable from(MetaChannelType type)
    {
        return metaChannels.get(type);
    }
}
