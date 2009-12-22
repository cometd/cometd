package org.cometd.bayeux.client;

import org.cometd.bayeux.MetaChannelType;

/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface ClientSession
{
    String getClientId();

    MetaChannel getMetaChannel(MetaChannelType meta);

    Channel getChannel(String channelName);

    void batch(Runnable batch);

    void disconnect();
}
