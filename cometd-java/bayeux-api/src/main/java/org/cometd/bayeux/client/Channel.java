package org.cometd.bayeux.client;

public interface Channel
{
    void subscribe(MessageListener listener);

    void unsubscribe(MessageListener listener);

    void publish(Object data);
}
