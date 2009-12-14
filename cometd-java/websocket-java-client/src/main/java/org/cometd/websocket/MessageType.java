package org.cometd.websocket;

import java.io.UnsupportedEncodingException;

/**
 * @version $Revision$ $Date$
 */
public enum MessageType
{
    TEXT((byte)0x00), BINARY((byte)0x80);

    public Message message(byte type, byte[] bytes)
    {
        MessageType messageType = MessageType.from(type);
        switch (messageType)
        {
            case TEXT:
                return new TextMessage(utf8Decode(bytes));
//            case BINARY:
//                return null; // TODO
            default:
                throw new WebSocketException();
        }
    }

    private static String utf8Decode(byte[] bytes)
    {
        try
        {
            return new String(bytes, "UTF-8");
        }
        catch (UnsupportedEncodingException x)
        {
            throw new WebSocketException(x);
        }
    }

    public static MessageType from(byte type)
    {
        switch (type)
        {
            case (byte)0x00:
                return TEXT;
            case (byte)0x80:
                return BINARY;
            default:
                throw new WebSocketException();
        }
    }

    private final byte type;

    private MessageType(byte type)
    {
        this.type = type;
    }

    public byte asByte()
    {
        return type;
    }

    public Message messageFrom(byte[] bytes)
    {
        return null;
    }
}
