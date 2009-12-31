package org.cometd.bwtp;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPChannel
{
    public static final String ALL_ID = "*";

    String getId();

    BWTPConnection getConnection();

    void header(Map<String, String> headers) throws ClosedChannelException;

    void message(byte[] content) throws ClosedChannelException;

    void close(Map<String, String> headers);
}
