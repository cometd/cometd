package org.cometd.bayeux.client.transport;

import java.util.Map;

import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date$
 */
public interface Transport
{
    void addListener(TransportListener listener);

    void removeListener(TransportListener listener);

    /**
     * @return the type of the transport, such as "long-polling",
     * to be used in bayeux handshake as connection type
     */
    String getType();

    boolean accept(String bayeuxVersion);

    void init();

    void destroy();

    MetaMessage.Mutable newMetaMessage(Map<String, Object> fields);

    void send(MetaMessage... metaMessages);
}
