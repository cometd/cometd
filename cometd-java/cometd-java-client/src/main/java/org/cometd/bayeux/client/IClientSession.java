package org.cometd.bayeux.client;

/**
 * @version $Revision$ $Date$
 */
public interface IClientSession extends ClientSession
{
    void subscribe(Channel channel);

    void unsubscribe(Channel channel);

    void publish(Channel channel, Object data);
}
