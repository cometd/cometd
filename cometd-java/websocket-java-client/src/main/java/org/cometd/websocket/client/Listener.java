package org.cometd.websocket.client;

import java.util.Map;

import org.cometd.websocket.Message;

/**
 * @version $Revision$ $Date$
 */
public interface Listener
{
    void onOpen(Map<String, String> headers);

    void onMessage(Message message);

    void onClose();

    void onConnectException(Throwable x);

    void onException(Throwable x);

    void onProtocolError();

    public static class Adapter implements Listener
    {
        public void onOpen(Map<String, String> headers)
        {
        }

        public void onMessage(Message message)
        {
        }

        public void onClose()
        {
        }

        public void onConnectException(Throwable x)
        {
        }

        public void onException(Throwable x)
        {
        }

        public void onProtocolError()
        {
        }
    }
}
