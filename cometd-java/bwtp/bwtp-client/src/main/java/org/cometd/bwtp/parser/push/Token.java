package org.cometd.bwtp.parser.push;

import java.nio.ByteBuffer;

/**
 * @version $Revision: 849 $ $Date$
 */
public class Token
{
    private byte[] bytes = new byte[256];
    private int index = 0;

    public void append(byte data)
    {
        bytes[index] = data;
        ++index;
        if (index == bytes.length)
        {
            // Grow the array
            byte[] newBytes = new byte[2 * bytes.length];
            System.arraycopy(bytes, 0, newBytes, 0, index);
            bytes = newBytes;
        }
    }

    public void reset()
    {
        index = 0;
    }

    public ByteBuffer getByteBuffer()
    {
        byte[] out = new byte[index];
        System.arraycopy(bytes, 0, out, 0, out.length);
        return ByteBuffer.wrap(out);
    }
}
