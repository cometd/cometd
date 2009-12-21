package org.cometd.websocket.client;

/**
 * @version $Revision$ $Date$
 */
public interface Client extends Session
{
    void addListener(Listener listener);

    void removeListener(Listener listener);

    boolean open();

    void open(OpenCallback callback);

    interface OpenCallback
    {
        void opened(Session session);
    }
}
