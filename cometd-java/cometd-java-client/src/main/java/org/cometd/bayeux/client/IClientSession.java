package org.cometd.bayeux.client;

import org.cometd.bayeux.Channel;

/**
 * TODO: this interface is only needed for channels, maybe there is no need that it extends ClientSession
 * @version $Revision$ $Date$
 */
public interface IClientSession extends ClientSession
{
    void subscribe(Channel channel);

    void unsubscribe(Channel channel);

    void publish(Channel channel, Object data);
}
