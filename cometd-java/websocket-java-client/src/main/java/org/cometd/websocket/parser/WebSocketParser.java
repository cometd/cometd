package org.cometd.websocket.parser;

import java.nio.ByteBuffer;

/**
 * @version $Revision$ $Date$
 */
public interface WebSocketParser
{
    WebSocketParserListener.Registration registerListener(WebSocketParserListener listener);

    void parse(ByteBuffer buffer);
}
