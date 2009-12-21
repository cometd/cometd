package org.cometd.websocket;

/**
 * @version $Revision$ $Date$
 */
public interface Message
{
    MessageType getType();

    byte[] encode();
}
