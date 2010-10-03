package org.cometd.common;

/**
 * @author gregw
 *
 * @deprecated Use org.cometd.bayeux.ChannelId
 */
@Deprecated 
public class ChannelId extends org.cometd.bayeux.ChannelId
{
    public ChannelId(String name)
    {
        super(name);
    }
}
