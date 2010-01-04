package org.cometd.bayeux.client;


import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Session;


public interface SessionChannel extends Channel
{
    Session getSession();

    void publish(Object data);
    void subscribe(MessageListener listener);

    void unsubscribe(MessageListener listener);
    void unsubscribe();
}
