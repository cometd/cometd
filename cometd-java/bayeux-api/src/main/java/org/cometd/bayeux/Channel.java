package org.cometd.bayeux;

public interface Channel
{
    String getName();

    void subscribe(MessageListener listener);

    void unsubscribe(MessageListener listener);

    void publish(Object data);

    interface Mutable extends Channel
    {
        void notifySubscribers(Message message);
    }
}
