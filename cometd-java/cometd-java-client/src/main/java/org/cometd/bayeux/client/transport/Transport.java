package org.cometd.bayeux.client.transport;

import java.util.Map;

import org.cometd.bayeux.CommonMessage;
import org.cometd.bayeux.IMessage;

/**
 * @version $Revision$ $Date$
 */
public interface Transport
{
    void addListener(TransportListener listener);

    void removeListener(TransportListener listener);

    /**
     * @return the type of the transport, such as "long-polling", to be used
     * in bayeux handshake messages and connect messages as connection type
     */
    String getType();

    boolean accept(String bayeuxVersion);

    void init();

    void destroy();

    IMessage.Mutable newMessage();

    IMessage.Mutable newMessage(Map<String, Object> fields);

    void send(CommonMessage.Mutable... metaMessages);
}
