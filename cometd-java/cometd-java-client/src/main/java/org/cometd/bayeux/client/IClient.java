package org.cometd.bayeux.client;

import bayeux.Message;
import bayeux.client.Client;

/**
 * @version $Revision$ $Date$
 */
public interface IClient extends Client
{
    void send(Message message);
}
