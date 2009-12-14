package org.cometd.websocket.client;

import org.cometd.websocket.Message;

/**
 * @version $Revision$ $Date$
 */
public interface Session
{
    void send(Message message);

    void close();
}
