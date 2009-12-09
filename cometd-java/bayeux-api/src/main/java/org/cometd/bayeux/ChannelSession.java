package org.cometd.bayeux;



public interface ChannelSession extends Channel,Client
{
    Subscription subscribe(MessageListener listener);
    void publish(Object data);
}
