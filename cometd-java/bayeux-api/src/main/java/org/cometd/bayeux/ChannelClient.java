package org.cometd.bayeux;



public interface ChannelClient extends Channel,Client
{
    Subscription subscribe(MessageListener listener);
    void publish(Object data);
}
