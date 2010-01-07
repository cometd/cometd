package org.cometd.bayeux.client.transport;

import org.cometd.bayeux.Message;

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

    Message.Mutable newMessage();

    void send(Message.Mutable... messages);
}
