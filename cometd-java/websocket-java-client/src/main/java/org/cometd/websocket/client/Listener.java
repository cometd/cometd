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

    public interface Registration
    {
        void unregister();
    }

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
    }
}
