package org.cometd.bayeux.client;

import java.util.EnumMap;

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
        metaChannels.put(MetaChannelType.DISCONNECT, new StandardMetaChannel(MetaChannelType.DISCONNECT));
    }

    public MetaChannel.Mutable from(MetaChannelType type)
    {
        return metaChannels.get(type);
    }

    public void notifySuscribers(MetaChannel.Mutable metaChannel, MetaMessage metaMessage)
    {
        // TODO: notify also globbed metachannels ?
        metaChannel.notifySubscribers(metaMessage);
    }
}
