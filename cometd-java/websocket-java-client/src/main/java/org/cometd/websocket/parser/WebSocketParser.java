package org.cometd.websocket.parser;

import java.nio.ByteBuffer;

/**
 * @version $Revision$ $Date$
 */
public interface WebSocketParser
{
    void addListener(WebSocketParserListener listener);

    void removeListener(WebSocketParserListener listener);

    void parse(ByteBuffer buffer);
}
