package org.cometd.bayeux.client;


import org.cometd.bayeux.Channel;


public interface SessionChannel extends Channel
{
    ClientSession getSession();

    void publish(Object data);
    void subscribe(ClientSession.MessageListener listener);

    void unsubscribe(ClientSession.MessageListener listener);
    void unsubscribe();
    
}
