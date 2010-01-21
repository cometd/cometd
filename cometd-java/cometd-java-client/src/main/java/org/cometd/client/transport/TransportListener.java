package org.cometd.client.transport;

import java.util.List;

import org.cometd.bayeux.Message;

/**
 * @version $Revision: 902 $ $Date$
 */
public interface TransportListener
{
    void onMessages(List<Message.Mutable> metaMessages);

    void onConnectException(Throwable x);

    void onException(Throwable x);

    void onExpire();

    void onProtocolError();
}
