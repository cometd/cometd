package org.cometd.websocket.generator;

import java.net.URI;
import java.nio.channels.ClosedChannelException;

import org.cometd.websocket.Message;

/**
 * @version $Revision$ $Date$
 */
public interface WebSocketGenerator
{
    void handshakeRequest(URI uri, String protocol) throws ClosedChannelException;

    void send(Message message) throws ClosedChannelException;
}
