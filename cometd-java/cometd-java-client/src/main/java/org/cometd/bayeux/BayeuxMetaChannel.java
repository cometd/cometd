package org.cometd.bayeux;

import bayeux.MetaChannel;
import bayeux.MetaChannelType;
import bayeux.ChannelSubscription;
import bayeux.MetaMessageListener;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxMetaChannel implements MetaChannel
{
    private final MetaChannelType type;

    public BayeuxMetaChannel(MetaChannelType type)
    {
        this.type = type;
    }

    public ChannelSubscription subscribe(MetaMessageListener listener)
    {
        return null;
    }

    public String getName()
    {
        return type.getName();
    }
}
