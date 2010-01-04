package org.cometd.websocket.generator;

import java.net.URI;

import org.cometd.websocket.Message;

/**
 * @version $Revision$ $Date$
 */
public interface WebSocketGenerator
{
    void handshakeRequest(URI uri, String protocol);

    void send(Message message);
}
