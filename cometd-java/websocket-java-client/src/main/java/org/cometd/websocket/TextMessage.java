package org.cometd.websocket;

import java.io.UnsupportedEncodingException;

/**
 * @version $Revision$ $Date$
 */
public final class TextMessage implements Message
{
    public static final byte END = (byte)0xFF;

    private String text;

    public TextMessage(String text)
    {
        this.text = text;
    }

    public byte[] asBytes()
    {
        byte[] utf8Bytes = utf8Encode(text);
        byte[] result = new byte[utf8Bytes.length + 2];
        result[0] = (byte)0x00;
        System.arraycopy(utf8Bytes, 0, result, 1, utf8Bytes.length);
        result[result.length - 1] = END;
        return result;
    }

    private byte[] utf8Encode(String text)
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
}
