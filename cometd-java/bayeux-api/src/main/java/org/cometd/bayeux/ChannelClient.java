package org.cometd.bayeux;



public interface ChannelClient
{
    Channel getChannel();
    Client getClient();
    Subscription subscribe(MessageListener listener);
    void publish(Object data);
}
