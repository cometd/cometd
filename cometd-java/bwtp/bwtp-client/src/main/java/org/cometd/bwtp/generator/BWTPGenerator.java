package org.cometd.bwtp.generator;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPGenerator
{
    String open(String path, Map<String, String> headers, boolean even) throws ClosedChannelException;

    void opened(String channelId, Map<String, String> headers) throws ClosedChannelException;

    void header(String channelId, Map<String, String> headers) throws ClosedChannelException;

    void message(String channelId, byte[] content) throws ClosedChannelException;

    void close(String channelId, Map<String, String> headers) throws ClosedChannelException;

    void closed(String channelId, Map<String, String> headers) throws ClosedChannelException;
}
