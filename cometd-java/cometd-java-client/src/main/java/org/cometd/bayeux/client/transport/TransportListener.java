package org.cometd.bayeux.client.transport;

import java.util.List;

import org.cometd.bayeux.CommonMessage;

/**
 * @version $Revision$ $Date$
 */
public interface TransportListener
{
    void onMessages(List<CommonMessage.Mutable> metaMessages);

    void onConnectException(Throwable x);

    void onException(Throwable x);

    void onExpire();

    void onProtocolError();

    public static class Adapter implements TransportListener
    {
        public void onMessages(List<CommonMessage.Mutable> metaMessages)
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
