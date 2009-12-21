package org.cometd.websocket;

import java.io.UnsupportedEncodingException;

/**
 * @version $Revision$ $Date$
 */
public final class TextMessage implements Message
{
    public static final byte END = (byte)0xFF;

    private final String text;

    public TextMessage(String text)
    {
        this.text = text;
    }

    public MessageType getType()
    {
        return MessageType.TEXT;
    }

    public String getText()
    {
        return text;
    }

    public byte[] encode()
    {
        byte[] utf8Bytes = utf8Encode(text);
        byte[] result = new byte[utf8Bytes.length + 2];
        result[0] = MessageType.TEXT.asByte();
        System.arraycopy(utf8Bytes, 0, result, 1, utf8Bytes.length);
        result[result.length - 1] = END;
        return result;
    }

    private static byte[] utf8Encode(String text)
    {
        try
        {
            return text.getBytes("UTF-8");
        }
        catch (UnsupportedEncodingException x)
        {
            throw new WebSocketException(x);
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

    public static TextMessage from(byte[] bytes)
    {
        return new TextMessage(utf8Decode(bytes));
    }
}
