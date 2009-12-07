package org.cometd.bayeux;

import java.util.EnumMap;

import bayeux.MetaChannel;
import bayeux.MetaChannelType;

/**
 * @version $Revision$ $Date$
 */
public class MetaChannels
{
    private final static EnumMap<MetaChannelType, MetaChannel> metaChannels = new EnumMap<MetaChannelType, MetaChannel>(MetaChannelType.class);

    static
    {
        metaChannels.put(MetaChannelType.HANDSHAKE, new BayeuxMetaChannel(MetaChannelType.HANDSHAKE));
        // TODO: add other channels
    }

    public static MetaChannel from(MetaChannelType type)
    {
        return metaChannels.get(type);
    }
}
