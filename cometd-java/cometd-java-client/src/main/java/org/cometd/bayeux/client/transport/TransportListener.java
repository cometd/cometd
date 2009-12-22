package org.cometd.bayeux.client.transport;

import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date$
 */
public interface TransportListener
{
    void onMetaMessages(MetaMessage.Mutable... metaMessages);

    void onConnectException(Throwable x);

    void onException(Throwable x);

    void onExpire();

    void onProtocolError();

    public static class Adapter implements TransportListener
    {
        public void onMetaMessages(MetaMessage.Mutable... metaMessages)
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
