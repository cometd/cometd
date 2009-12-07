package bayeux.client;

import bayeux.Channel;

/**
 * @version $Revision$ $Date$
 */
public interface Session
{
    Channel channel(String channelName);

    void batch(Runnable batch);

    void disconnect();
}
