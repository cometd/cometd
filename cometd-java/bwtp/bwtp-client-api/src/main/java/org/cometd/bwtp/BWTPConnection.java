package org.cometd.bwtp;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPConnection
{
    void addListener(BWTPListener listener);

    void removeListener(BWTPListener listener);

    BWTPChannel open(String path, Map<String, String> headers) throws ClosedChannelException;

    BWTPChannel findChannel(String channelId);

    void close(Map<String, String> headers);
}
