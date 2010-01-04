package org.cometd.websocket.client;

/**
 * @version $Revision$ $Date$
 */
public interface Client
{
    Listener.Registration registerListener(Listener listener);

    Session open();

    boolean open(OpenCallback callback);

    interface OpenCallback
    {
        void opened(Session session);
    }
}
