package org.cometd.client.transport;

import java.util.List;

import org.cometd.bayeux.Message;

/**
 * @version $Revision: 902 $ $Date$
 */
public interface TransportListener
{
    void onSending(Message[] messages);

    void onMessages(List<Message.Mutable> messages);

    void onConnectException(Throwable x, Message[] messages);

    void onException(Throwable x, Message[] messages);

    void onExpire(Message[] messages);

    void onProtocolError(String info, Message[] messages);
}
