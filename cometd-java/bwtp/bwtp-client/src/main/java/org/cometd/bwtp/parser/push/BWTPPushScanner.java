package org.cometd.bwtp.parser.push;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * @version $Revision$ $Date$
 */
public abstract class BWTPPushScanner
{
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public abstract boolean scan(ByteBuffer buffer, int limit);

    protected boolean isWhitespace(byte b)
    {
        return Character.isWhitespace(b);
    }

    protected boolean isLineSeparator(byte b)
    {
        return b == '\r' || b == '\n';
    }

    protected String utf8Decode(ByteBuffer buffer)
    {
        return UTF8.decode(buffer).toString();
    }
}
