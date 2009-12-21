package org.cometd.websocket;

/**
 * @version $Revision$ $Date$
 */
public enum MessageType
{
    TEXT((byte)0x00), BINARY((byte)0x80);

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
        switch (this)
        {
            case TEXT:
                return TextMessage.from(bytes);
//            case BINARY:
//                return null; // TODO
            default:
                throw new WebSocketException();
        }
    }
}
