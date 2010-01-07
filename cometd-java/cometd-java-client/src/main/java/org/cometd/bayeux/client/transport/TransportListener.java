package org.cometd.bayeux.client.transport;

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

    public static class Adapter implements TransportListener
    {
        public void onMessages(List<Message.Mutable> metaMessages)
        {
        }

        public void onConnectException(Throwable x)
        {
        }

        public void onException(Throwable x)
        {
        }

        public void onExpire()
        {
        }

        public void onProtocolError()
        {
        }
    }
}
