package org.cometd.bwtp;

import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface IBWTPConnection extends BWTPConnection
{
    void opened(String channelId, Map<String, String> headers);

    void close(String channelId, Map<String, String> headers);

    void closed(String channelId, Map<String, String> headers);
}
