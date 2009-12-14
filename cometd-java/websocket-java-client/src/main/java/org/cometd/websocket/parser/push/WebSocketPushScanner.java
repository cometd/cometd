package org.cometd.websocket.parser.push;

import java.nio.ByteBuffer;

/**
 * @version $Revision$ $Date$
 */
abstract class WebSocketPushScanner
{
    public abstract boolean scan(ByteBuffer buffer);

    protected boolean isWhitespace(byte b)
    {
        return Character.isWhitespace(b);
    }

    protected boolean isLineSeparator(byte b)
    {
        return b == '\r' || b == '\n';
    }
}
