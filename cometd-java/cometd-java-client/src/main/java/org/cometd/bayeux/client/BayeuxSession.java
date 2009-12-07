package org.cometd.bayeux.client;

import bayeux.client.Session;
import bayeux.Channel;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxSession implements Session
{
    public Channel channel(String channelName)
    {
        return null;
    }

    public void batch(Runnable batch)
    {
    }

    public void disconnect()
    {
    }
}
